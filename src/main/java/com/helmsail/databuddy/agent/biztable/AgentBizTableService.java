package com.helmsail.databuddy.agent.biztable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.agent.AgentMapper;
import com.helmsail.databuddy.agent.EmbeddingStatus;
import com.helmsail.databuddy.bizdatabase.BizDatabaseService;
import com.helmsail.databuddy.bizdatabase.jdbc.model.ColumnInfo;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableInfo;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.vectorize.IndexSourceType;
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

	public AgentBizTableService(AgentBizTableMapper mapper, AgentMapper agentMapper,
			BizDatabaseService bizDatabaseService, VectorService vectorService) {
		this.mapper = mapper;
		this.agentMapper = agentMapper;
		this.bizDatabaseService = bizDatabaseService;
		this.vectorService = vectorService;
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
		for (TableInfo table : bizDatabaseService.listTables(databaseConfigId)) {
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
			mapper.deleteById(row.getId());
			vectorService.deleteBySource(agentId, IndexSourceType.BIZ_TABLE, row.getId());
		}
		log.info("表解绑完成: agent={}, 请求 {} 张", agentId, ids.size());
	}

	/** 全量批量向量化:全部绑定表(绑定后首刷 / 整体重建);单行失败不中断整批,FAILED + 原因落库待重试 */
	public void sync(long agentId) {
		syncRows(agentId, mapper.selectByAgent(agentId));
	}

	/** 增量重试:仅处理未同步行(PENDING / FAILED);手动重试与定时兜底共用,幂等可反复调 */
	public void retryUnsynced(long agentId) {
		List<AgentBizTable> rows = mapper.selectByAgent(agentId).stream()
			.filter(row -> row.getEmbeddingStatus() != EmbeddingStatus.SYNCED)
			.toList();
		syncRows(agentId, rows);
	}

	/** 兜底扫尾:逐个 agent 重试未同步行(定时任务入口;无待重试行时静默) */
	public void retryUnsyncedAll() {
		List<Long> agentIds = mapper.selectAgentIdsUnsynced();
		for (Long agentId : agentIds) {
			retryUnsynced(agentId);
		}
		if (!agentIds.isEmpty()) {
			log.info("兜底重试完成: 涉及 {} 个 agent", agentIds.size());
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
				List<ColumnInfo> columns = bizDatabaseService.listColumns(row.getDatabaseConfigId(), row.getTableName());
				String content = buildContent(row.getTableName(), tableComment, columns);
				vectorService.index(agentId, IndexSourceType.BIZ_TABLE, row.getId(), SplitterType.WHOLE, content);
				mapper.updateSyncStatus(row.getId(), EmbeddingStatus.SYNCED, null);
				synced++;
			}
			catch (Exception e) {
				String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
				log.warn("表向量化失败: agent={}, table={}", agentId, row.getTableName(), e);
				mapper.updateSyncStatus(row.getId(), EmbeddingStatus.FAILED, truncate(reason));
			}
		}
		log.info("表向量化完成: agent={}, 成功 {}/{}", agentId, synced, rows.size());
	}

	/** 表注释:按库缓存一次表清单(注释可能为 null) */
	private String tableComment(Map<Long, Map<String, String>> commentsByConfig, long configId, String tableName) {
		Map<String, String> comments = commentsByConfig.computeIfAbsent(configId, id -> {
			Map<String, String> map = new HashMap<>();
			for (TableInfo table : bizDatabaseService.listTables(id)) {
				map.put(table.getName(), table.getComment());
			}
			return map;
		});
		return comments.get(tableName);
	}

	/** 向量化文本:表名 + 表注释 + 列(名 / 类型 / 注释);注释缺失只省略、不失败(内容兜底) */
	private String buildContent(String tableName, String tableComment, List<ColumnInfo> columns) {
		StringBuilder content = new StringBuilder("表: ").append(tableName);
		if (StringUtils.hasText(tableComment)) {
			content.append("(").append(tableComment).append(")");
		}
		content.append('\n');
		for (ColumnInfo column : columns) {
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
