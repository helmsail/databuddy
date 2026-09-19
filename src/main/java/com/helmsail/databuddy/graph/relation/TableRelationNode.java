package com.helmsail.databuddy.graph.relation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.helmsail.databuddy.agent.AgentService;
import com.helmsail.databuddy.agent.RetrievedChunk;
import com.helmsail.databuddy.bizdatabase.BizTableRelation;
import com.helmsail.databuddy.bizdatabase.RelationType;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.vectorize.IndexSourceType;

import lombok.extern.slf4j.Slf4j;

/**
 * 表关系节点:数据链第四节点。取与召回表集相关的人工表关系(join 条件),并把关系里出现、
 * 但没被召回到的关联表按名精确重召回补进来,形成"最终可用表集 + 关系清单"写回
 * SCHEMA / RECALLED_TABLES / TABLE_RELATIONS。零 LLM;无关系不终止(单表分析合理),
 * 补拉失败记日志跳过;阻塞调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class TableRelationNode implements AsyncNodeAction {

	/** 补拉来源:表块 */
	private static final EnumSet<IndexSourceType> TABLE_SOURCE = EnumSet.of(IndexSourceType.BIZ_TABLE);

	private final AgentService agentService;

	public TableRelationNode(AgentService agentService) {
		this.agentService = agentService;
	}

	@Override
	@Observed(name = "node.tableRelation", contextualName = "表关系补齐")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		long agentId = state.value(GraphKeys.AGENT_ID, Long.class).orElse(0L);
		List<String> seeds = seedTables(state);
		if (seeds.isEmpty()) {
			log.warn("表关系节点收到空表集,跳过: agent={}", agentId);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.NODE_STATUS, "表关系补齐跳过:无召回表"));
		}
		List<BizTableRelation> relations = agentService.relationsOf(agentId, seeds);
		Set<String> finalTables = new LinkedHashSet<>(seeds);
		List<String> pulledNames = new ArrayList<>();
		List<String> pulledContents = new ArrayList<>();
		for (String missing : missingEndpoints(relations, finalTables)) {
			RetrievedChunk chunk = recallByName(agentId, missing);
			if (chunk == null) {
				log.warn("关联表补拉未命中,跳过: agent={}, table={}", agentId, missing);
				continue;
			}
			pulledNames.add(missing);
			pulledContents.add(chunk.content());
			finalTables.add(missing);
		}
		List<String> relationLines = relations.stream()
			.filter(relation -> finalTables.contains(relation.getSourceTableName())
					&& finalTables.contains(relation.getTargetTableName()))
			.map(this::format)
			.distinct()
			.toList();
		log.info("表关系补齐: agent={}, 关系 {} 条, 补拉 {} 张: {}", agentId, relationLines.size(), pulledNames.size(),
				pulledNames);
		return CompletableFuture.completedFuture(Map.of(GraphKeys.SCHEMA, merge(state, pulledContents),
				GraphKeys.RECALLED_TABLES, List.copyOf(finalTables), GraphKeys.TABLE_RELATIONS, relationsText(relationLines),
				GraphKeys.NODE_STATUS, note(relationLines.size(), pulledNames)));
	}

	/** 读召回表集(前节点写入;防御性取值) */
	private List<String> seedTables(OverAllState state) {
		Object raw = state.value(GraphKeys.RECALLED_TABLES).orElse(null);
		if (raw instanceof List<?> list) {
			return list.stream().map(String::valueOf).toList();
		}
		return List.of();
	}

	/** 关系里出现、但尚未在集合中的表(去重保序) */
	private List<String> missingEndpoints(List<BizTableRelation> relations, Set<String> known) {
		List<String> missing = new ArrayList<>();
		for (BizTableRelation relation : relations) {
			collectMissing(missing, known, relation.getSourceTableName());
			collectMissing(missing, known, relation.getTargetTableName());
		}
		return missing;
	}

	private void collectMissing(List<String> missing, Set<String> known, String table) {
		if (table != null && !known.contains(table) && !missing.contains(table)) {
			missing.add(table);
		}
	}

	/** 按表名精确重召回:命中块的解析名必须与请求名一致才算数(防语义漂移) */
	private RetrievedChunk recallByName(long agentId, String tableName) {
		List<RetrievedChunk> hits = agentService.retrieve(agentId, tableName, 1, TABLE_SOURCE);
		if (hits.isEmpty()) {
			return null;
		}
		RetrievedChunk hit = hits.get(0);
		return tableName.equals(NodeUtils.parseTableName(hit.content())) ? hit : null;
	}

	/** 关系一行:order_main.id = order_item.order_id(1:N) */
	private String format(BizTableRelation relation) {
		return relation.getSourceTableName() + "." + relation.getSourceColumnName() + " = "
				+ relation.getTargetTableName() + "." + relation.getTargetColumnName() + "("
				+ marker(relation.getRelationType()) + ")";
	}

	/** 数量关系标记(方向:源 → 目标) */
	private String marker(RelationType type) {
		if (type == null) {
			return "未知";
		}
		return switch (type) {
			case ONE_TO_ONE -> "1:1";
			case ONE_TO_MANY -> "1:N";
			case MANY_TO_ONE -> "N:1";
			case MANY_TO_MANY -> "N:N";
		};
	}

	/** SCHEMA 追加补拉块(各块 trim 后换行分隔) */
	private String merge(OverAllState state, List<String> pulledContents) {
		StringBuilder merged = new StringBuilder(state.value(GraphKeys.SCHEMA, String.class).orElse(""));
		for (String content : pulledContents) {
			merged.append('\n').append(content.trim()).append('\n');
		}
		return merged.toString().trim();
	}

	/** 关系清单文本(多行;无关系为"无") */
	private String relationsText(List<String> relationLines) {
		return relationLines.isEmpty() ? "无" : String.join("\n", relationLines);
	}

	/** 过程播报:关系条数 + 补拉明细 */
	private String note(int relationCount, List<String> pulledNames) {
		String note = relationCount == 0 ? "表关系补齐完成:未找到相关表关系" : "表关系补齐完成:关系 " + relationCount + " 条";
		return pulledNames.isEmpty() ? note
				: note + ",补拉关联表 " + pulledNames.size() + " 张(" + String.join(", ", pulledNames) + ")";
	}

}
