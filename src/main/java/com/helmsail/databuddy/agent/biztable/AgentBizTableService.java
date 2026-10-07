package com.helmsail.databuddy.agent.biztable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.agent.Agent;
import com.helmsail.databuddy.agent.AgentMapper;
import com.helmsail.databuddy.agent.EmbeddingStatus;
import com.helmsail.databuddy.bizdatabase.BizDatabaseService;
import com.helmsail.databuddy.bizdatabase.jdbc.model.ColumnMeta;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableMeta;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.vectorize.DelegatingEmbeddingModel;
import com.helmsail.databuddy.vectorize.KnowledgeType;
import com.helmsail.databuddy.vectorize.VectorService;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;

/**
 * 表绑定服务:agent_biz_table 行的生命周期(绑定 / 解绑 / 列表)与批量向量同步。
 * 向量化粒度 = 整表一块(WHOLE):表名 + 表注释 + 列(名 / 类型 / 注释);
 * 唯一出入口:业务库结构走 BizDatabaseService、向量走 VectorService;单行失败不中断整批,状态落库待重试
 */
@Slf4j
@Service
public class AgentBizTableService {

	/** 失败原因落库长度上限(error_msg 列宽 512,留余量) */
	private static final int ERROR_MSG_MAX = 500;

	private final AgentBizTableMapper mapper;

	private final AgentMapper agentMapper;

	private final BizDatabaseService bizDatabaseService;

	private final VectorService vectorService;

	/** 委托门面:取当前模型名(删除只清现役分区) */
	private final DelegatingEmbeddingModel embeddingModel;

	public AgentBizTableService(AgentBizTableMapper mapper, AgentMapper agentMapper,
			BizDatabaseService bizDatabaseService, VectorService vectorService, DelegatingEmbeddingModel embeddingModel) {
		this.mapper = mapper;
		this.agentMapper = agentMapper;
		this.bizDatabaseService = bizDatabaseService;
		this.vectorService = vectorService;
		this.embeddingModel = embeddingModel;
	}

	/** 某 agent 的绑定清单(含向量化状态) */
	public List<AgentBizTable> list(long agentId) {
		return mapper.selectByAgent(agentId);
	}

	/** 绑定表:agent 必须存在;校验业务库可达、表名真实存在;已绑定的幂等跳过,新行置 PENDING 待同步 */
	@Transactional
	public void bind(long agentId, long databaseConfigId, List<String> tableNames) {
		if (tableNames == null || tableNames.isEmpty()) {
			return;
		}
		if (agentMapper.selectById(agentId) == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "agent 不存在: " + agentId);
		}
		Set<String> existing = new HashSet<>();
		for (TableMeta table : bizDatabaseService.listTables(databaseConfigId)) {
			existing.add(table.getName());
		}
		Set<String> bound = new HashSet<>();
		for (AgentBizTable row : mapper.selectByAgent(agentId)) {
			if (row.getDatabaseConfigId() == databaseConfigId) {
				bound.add(row.getTableName());
			}
		}
		int added = 0;
		for (String tableName : tableNames) {
			if (!existing.contains(tableName)) {
				throw new BusinessException(ErrorCode.INVALID_INPUT, "业务库中不存在表: " + tableName);
			}
			if (bound.contains(tableName)) {
				continue;
			}
			AgentBizTable row = new AgentBizTable();
			row.setAgentId(agentId);
			row.setDatabaseConfigId(databaseConfigId);
			row.setTableName(tableName);
			row.setEmbeddingStatus(EmbeddingStatus.PENDING);
			mapper.insert(row);
			added++;
		}
		log.info("表绑定完成: agent={}, 库={}, 请求 {} 张, 新增 {} 张", agentId, databaseConfigId, tableNames.size(), added);
	}

	/** 解绑:物理删行并逐行删除对应向量;id 不属于该 agent 的静默跳过 */
	@Transactional
	public void unbind(long agentId, List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return;
		}
		Set<Long> idSet = new HashSet<>(ids);
		for (AgentBizTable row : mapper.selectByAgent(agentId)) {
			if (!idSet.contains(row.getId())) {
				continue;
			}
			mapper.deleteById(agentId, row.getId());
			vectorService.deleteByDims(agentId, embeddingModel.modelName(), KnowledgeType.TABLE, row.getId());
		}
		log.info("表解绑完成: agent={}, 请求 {} 张", agentId, ids.size());
	}

	/** 全量批量向量化:全部绑定表(绑定后首刷 / 整体重建);单行失败不中断整批,FAILED + 原因落库待重试 */
	public void sync(long agentId) {
		syncRows(agentId, mapper.selectByAgent(agentId));
	}

	/** 增量重试:处理某 agent 全部未同步行(PENDING / FAILED 各查一次);手动重试与定时兜底共用,幂等可反复调 */
	public void retryUnsynced(long agentId) {
		List<AgentBizTable> rows = mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.PENDING);
		rows.addAll(mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.FAILED));
		syncRows(agentId, rows);
	}

	/** 模型切换失效:把已同步行标记 FAILED(原因给定),交重试 / 定时兜底在新模型分区重建(旧分区向量保留,切回即恢复) */
	public void invalidateSynced(long agentId, String reason) {
		List<AgentBizTable> rows = mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.SYNCED);
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizTable row : rows) {
			writeStatus(row, EmbeddingStatus.FAILED, truncate(reason));
		}
		log.info("表模型切换失效: agent={}, 共 {} 张待重建", agentId, rows.size());
	}

	/** 兜底扫尾:逐 agent 重试未同步行(定时任务入口;无待重试行即空跑,天然静默) */
	public void retryUnsyncedAll() {
		for (Agent agent : agentMapper.selectAll()) {
			retryUnsynced(agent.getId());
		}
	}

	/** 逐行同步核心:读结构 → 拼文本 → 索引;单行失败不中断整批,FAILED + 原因落库待重试 */
	private void syncRows(long agentId, List<AgentBizTable> rows) {
		if (rows.isEmpty()) {
			return;
		}
		Map<Long, Map<String, String>> commentsByConfig = new HashMap<>();
		int synced = 0;
		for (AgentBizTable row : rows) {
			try {
				String tableComment = tableComment(commentsByConfig, row.getDatabaseConfigId(), row.getTableName());
				List<ColumnMeta> columns = bizDatabaseService.listColumns(row.getDatabaseConfigId(), row.getTableName());
				String content = buildContent(row.getTableName(), tableComment, columns);
				vectorService.index(agentId, KnowledgeType.TABLE, row.getId(), SplitterType.WHOLE, content);
				writeStatus(row, EmbeddingStatus.SYNCED, null);
				synced++;
			}
			catch (Exception e) {
				String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
				log.warn("表向量化失败: agent={}, table={}", agentId, row.getTableName(), e);
				writeStatus(row, EmbeddingStatus.FAILED, truncate(reason));
			}
		}
		log.info("表向量化完成: agent={}, 成功 {}/{}", agentId, synced, rows.size());
	}

	/** 状态回执(CAS + 迁移校验):仅当行仍为读取时状态才落新态;0 行 = 状态已变或行已删,回执未生效 */
	private void writeStatus(AgentBizTable row, EmbeddingStatus to, String errorMsg) {
		EmbeddingStatus from = row.getEmbeddingStatus();
		if (from == null || !from.canTransitionTo(to)) {
			log.warn("非法状态迁移被挡: {} -> {} (#{})", from, to, row.getId());
			return;
		}
		int rows = mapper.updateSyncStatus(row.getAgentId(), row.getId(), from, to, errorMsg);
		if (rows == 0) {
			log.warn("状态回执未生效(状态已变或行已删): #{} {} -> {}", row.getId(), from, to);
			return;
		}
		row.setEmbeddingStatus(to);
		row.setErrorMsg(errorMsg);
	}

	/** 表注释:按库缓存一次表清单(注释可能为 null) */
	private String tableComment(Map<Long, Map<String, String>> commentsByConfig, long configId, String tableName) {
		Map<String, String> comments = commentsByConfig.computeIfAbsent(configId, id -> {
			Map<String, String> map = new HashMap<>();
			for (TableMeta table : bizDatabaseService.listTables(id)) {
				map.put(table.getName(), table.getComment());
			}
			return map;
		});
		return comments.get(tableName);
	}

	/** 向量化文本:表名 + 表注释 + 列(名 / 类型 / 注释);注释缺失只省略、不失败(内容兜底) */
	private String buildContent(String tableName, String tableComment, List<ColumnMeta> columns) {
		StringBuilder content = new StringBuilder("表: ").append(tableName);
		if (StringUtils.hasText(tableComment)) {
			content.append("(").append(tableComment).append(")");
		}
		content.append('\n');
		for (ColumnMeta column : columns) {
			content.append(column.getName()).append(' ').append(column.getDataType());
			if (StringUtils.hasText(column.getComment())) {
				content.append(" - ").append(column.getComment());
			}
			content.append('\n');
		}
		return content.toString();
	}

	/** 失败原因截断到列宽上限(NULL 安全) */
	private String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() <= ERROR_MSG_MAX ? message : message.substring(0, ERROR_MSG_MAX);
	}

}
