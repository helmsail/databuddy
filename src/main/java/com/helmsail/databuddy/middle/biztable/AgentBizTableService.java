package com.helmsail.databuddy.middle.biztable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.bottom.bizdatabase.BizDatabaseConfig;
import com.helmsail.databuddy.bottom.bizdatabase.BizDatabaseService;
import com.helmsail.databuddy.bottom.bizdatabase.BizTableRelation;
import com.helmsail.databuddy.bottom.bizdatabase.jdbc.config.DbType;
import com.helmsail.databuddy.bottom.bizdatabase.jdbc.model.ColumnMeta;
import com.helmsail.databuddy.bottom.bizdatabase.jdbc.model.TableMeta;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.bottom.vectorize.KnowledgeType;
import com.helmsail.databuddy.bottom.vectorize.RetrievedChunk;
import com.helmsail.databuddy.bottom.vectorize.VectorService;
import com.helmsail.databuddy.bottom.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;

/**
 * 表绑定服务:agent_biz_table 行的生命周期(绑定 / 解绑 / 列表)、向量刷新与图侧取数用例(检索 / 关系 / 目标库)。
 * 刷新入口(人工,同步):实时查业务库结构重刷全部绑定表——绑定首刷 / 结构变化 / 模型切换后,使用前调用一次;
 * 无状态字段与定时任务:业务库结构会漂移,"曾同步成功"不代表新鲜,用前重刷最诚实;
 * 单表失败只记日志不中断整批;向量走 VectorService(先删后写,重复刷新幂等)
 */
@Slf4j
@Service
public class AgentBizTableService {

	private final AgentBizTableMapper mapper;

	private final BizDatabaseService bizDatabaseService;

	private final VectorService vectorService;

	public AgentBizTableService(AgentBizTableMapper mapper, BizDatabaseService bizDatabaseService,
			VectorService vectorService) {
		this.mapper = mapper;
		this.bizDatabaseService = bizDatabaseService;
		this.vectorService = vectorService;
	}

	/** 某 agent 的绑定清单 */
	public List<AgentBizTable> list(long agentId) {
		return mapper.selectByAgent(agentId);
	}

	/** 绑定表(agent 存在性由入口校验):校验业务库可达、表名真实存在;已绑定的幂等跳过(首刷由前端连带调用刷新入口) */
	@Transactional
	public void bind(long agentId, long databaseConfigId, List<String> tableNames) {
		if (tableNames == null || tableNames.isEmpty()) {
			return;
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

	// ============ 图侧取数用例(检索 / 关系 / 目标库;供图节点直连) ============

	/** 图侧检索:主查询/表名向量命中表块(整表一块,内容自足;供 Schema 召回与表关系补拉) */
	public List<RetrievedChunk> retrieve(long agentId, String query, int topK) {
		List<Document> hits = vectorService.search(agentId, query, topK, KnowledgeType.TABLE);
		List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
		for (Document hit : hits) {
			chunks.add(RetrievedChunk.of(hit, Map.of()));
		}
		return chunks;
	}

	/**
	 * 取与指定表集相关的表关系:按绑定行定位这些表所属的业务库 → 逐库取关系 → 只保留"源表或目标表命中给定表集"的行;
	 * 供表关系节点做 join 补齐(零 LLM)
	 */
	public List<BizTableRelation> relationsOf(long agentId, Collection<String> tableNames) {
		if (tableNames == null || tableNames.isEmpty()) {
			return List.of();
		}
		Set<String> names = Set.copyOf(tableNames);
		Set<Long> configIds = list(agentId).stream()
			.filter(row -> names.contains(row.getTableName()))
			.map(AgentBizTable::getDatabaseConfigId)
			.collect(Collectors.toSet());
		List<BizTableRelation> relations = new ArrayList<>();
		for (Long configId : configIds) {
			for (BizTableRelation relation : bizDatabaseService.listRelations(configId)) {
				if (names.contains(relation.getSourceTableName()) || names.contains(relation.getTargetTableName())) {
					relations.add(relation);
				}
			}
		}
		log.info("表关系查询: agent={}, 表集 {} 张, 命中关系 {} 条", agentId, names.size(), relations.size());
		return relations;
	}

	/** 数据分析目标库:配置 id + 方言文本(图内 SQL 组节点共用:提示词用方言、执行用连接) */
	public record DatabaseTarget(long configId, String dialect) {
	}

	/**
	 * 解析智能体分析目标库:按召回表定位所属库配置(命中表所属库优先;无命中时仅当绑定表同属一库取唯一);
	 * 判不出返回 null(由节点侧写终止语);零 LLM
	 */
	public DatabaseTarget databaseTargetOf(long agentId, Collection<String> tableNames) {
		List<AgentBizTable> rows = list(agentId);
		if (rows.isEmpty()) {
			log.warn("无法判定分析目标库: agent={}, 未绑定任何数据表", agentId);
			return null;
		}
		Set<String> names = tableNames == null || tableNames.isEmpty() ? Set.of() : Set.copyOf(tableNames);
		AgentBizTable hit = rows.stream().filter(row -> names.contains(row.getTableName())).findFirst().orElse(null);
		if (hit == null) {
			Set<Long> configIds = rows.stream().map(AgentBizTable::getDatabaseConfigId).collect(Collectors.toSet());
			if (configIds.size() != 1) {
				log.warn("无法判定分析目标库: agent={}, 候选库 {} 个, 召回表均未命中绑定", agentId, configIds.size());
				return null;
			}
			hit = rows.get(0);
		}
		BizDatabaseConfig config = bizDatabaseService.getConfig(hit.getDatabaseConfigId());
		return new DatabaseTarget(config.getId(), dialect(config.getDbType()));
	}

	/** 库类型 → 提示词用方言名 */
	private String dialect(DbType dbType) {
		return dbType == DbType.MYSQL ? "MySQL" : dbType.name();
	}

}
