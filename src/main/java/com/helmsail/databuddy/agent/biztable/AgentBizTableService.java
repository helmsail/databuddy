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
import com.helmsail.databuddy.bizdatabase.BizDatabaseService;
import com.helmsail.databuddy.bizdatabase.jdbc.model.ColumnMeta;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableMeta;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.vectorize.KnowledgeType;
import com.helmsail.databuddy.vectorize.VectorService;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;

/**
 * 表绑定服务:agent_biz_table 行的生命周期(绑定 / 解绑 / 列表)与向量刷新。
 * 刷新入口(人工,同步):实时查业务库结构重刷全部绑定表——绑定首刷 / 结构变化 / 模型切换后,使用前调用一次;
 * 无状态字段与定时任务:业务库结构会漂移,"曾同步成功"不代表新鲜,用前重刷最诚实;
 * 单表失败只记日志不中断整批;向量走 VectorService(先删后写,重复刷新幂等)
 */
@Slf4j
@Service
public class AgentBizTableService {

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

	/** 某 agent 的绑定清单 */
	public List<AgentBizTable> list(long agentId) {
		return mapper.selectByAgent(agentId);
	}

	/** 绑定表:agent 必须存在;校验业务库可达、表名真实存在;已绑定的幂等跳过(首刷由前端连带调用刷新入口) */
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
			vectorService.deleteEntry(agentId, KnowledgeType.TABLE, row.getId());
		}
		log.info("表解绑完成: agent={}, 请求 {} 张", agentId, ids.size());
	}

	/** 刷新表向量(人工入口,同步):实时查业务库结构重刷全部绑定表(内容 = 表名 + 表注释 + 列名/类型/注释,整表一块);
	 * 表注释按库缓存一次表清单;单表失败只记日志不中断整批 */
	public void sync(long agentId) {
		List<AgentBizTable> rows = mapper.selectByAgent(agentId);
		if (rows.isEmpty()) {
			return;
		}
		Map<Long, Map<String, String>> commentsByConfig = new HashMap<>();
		int synced = 0;
		for (AgentBizTable row : rows) {
			try {
				Map<String, String> comments = commentsByConfig.computeIfAbsent(row.getDatabaseConfigId(), id -> {
					Map<String, String> map = new HashMap<>();
					for (TableMeta table : bizDatabaseService.listTables(id)) {
						map.put(table.getName(), table.getComment());
					}
					return map;
				});
				String tableComment = comments.get(row.getTableName());
				List<ColumnMeta> columns = bizDatabaseService.listColumns(row.getDatabaseConfigId(), row.getTableName());
				StringBuilder content = new StringBuilder("表: ").append(row.getTableName());
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
				vectorService.index(agentId, KnowledgeType.TABLE, row.getId(), SplitterType.WHOLE, content.toString());
				synced++;
			}
			catch (Exception e) {
				log.warn("表向量刷新失败: agent={}, table={}", agentId, row.getTableName(), e);
			}
		}
		log.info("表向量刷新完成: agent={}, 成功 {}/{}", agentId, synced, rows.size());
	}

}
