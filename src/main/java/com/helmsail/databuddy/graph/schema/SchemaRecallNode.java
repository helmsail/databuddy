package com.helmsail.databuddy.graph.schema;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.helmsail.databuddy.agent.AgentService;
import com.helmsail.databuddy.agent.RetrievedChunk;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.vectorize.IndexSourceType;

import lombok.extern.slf4j.Slf4j;

/**
 * Schema 召回节点:数据链第三节点。用规范查询向量召回 agent 绑定的表块(表名+注释+全列,块自足),
 * 拼接为 SCHEMA 文本、解析出表名写 RECALLED_TABLES;纯检索,不调 LLM。
 * 未命中:写终止语到 FINAL_ANSWER(经既有 END 机制播报给用户)与过程状态,流程收束;
 * 阻塞的检索调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class SchemaRecallNode implements AsyncNodeAction {

	/** 召回条数上限(整表一块,即最多召回的表的张数;偏宁多勿漏,关系补拉再兜底) */
	private static final int TOP_K = 8;

	/** 表块来源(知识源归知识召回,表块归本节点) */
	private static final EnumSet<IndexSourceType> TABLE_SOURCE = EnumSet.of(IndexSourceType.BIZ_TABLE);

	/** 未命中终止语(用户可见) */
	private static final String NO_TABLE_MESSAGE = "未检索到与问题相关的数据表,本轮分析无法继续。"
			+ "可能原因:1) 智能体尚未绑定数据表;2) 表向量化未完成或失败;3) 问题与已绑定表的结构无关。";

	private final AgentService agentService;

	public SchemaRecallNode(AgentService agentService) {
		this.agentService = agentService;
	}
    
	@Override
	@Observed(name = "node.schemaRecall", contextualName = "Schema 召回")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String canonical = state.value(GraphKeys.CANONICAL_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		long agentId = NodeUtils.longOf(state, GraphKeys.AGENT_ID);
		List<RetrievedChunk> tables = agentService.retrieve(agentId, canonical, TOP_K, TABLE_SOURCE);
		if (tables.isEmpty()) {
			log.warn("Schema 召回未命中: agent={}, 查询=\"{}\"", agentId, canonical);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SCHEMA, "无", GraphKeys.RECALLED_TABLES, List.of(),
					GraphKeys.NODE_STATUS, "Schema 召回未命中:未检索到相关数据表", GraphKeys.FINAL_ANSWER, NO_TABLE_MESSAGE));
		}
		List<String> names = names(tables);
		log.info("Schema 召回: agent={}, 命中 {} 张表: {}", agentId, tables.size(), names);
		return CompletableFuture.completedFuture(Map.of(GraphKeys.SCHEMA, join(tables), GraphKeys.RECALLED_TABLES, names,
				GraphKeys.NODE_STATUS, note(tables.size(), names)));
	}

	/** 各块内容拼接为 schema 文本(块自足:表名+注释+全列,零加工) */
	private String join(List<RetrievedChunk> tables) {
		StringBuilder schema = new StringBuilder();
		for (RetrievedChunk table : tables) {
			schema.append(table.content()).append('\n');
		}
		return schema.toString().trim();
	}

	/** 解析表名(约定在 NodeUtils.parseTableName;解析不到只是少个名字,流程判断用块数,不受影响) */
	private List<String> names(List<RetrievedChunk> tables) {
		List<String> names = new ArrayList<>(tables.size());
		for (RetrievedChunk table : tables) {
			String name = NodeUtils.parseTableName(table.content());
			if (name != null) {
				names.add(name);
			}
		}
		return names;
	}

	/** 过程播报:命中张数 + 表名(未解析到名字时只报张数) */
	private String note(int count, List<String> names) {
		return names.isEmpty() ? "Schema 召回完成:命中 " + count + " 张表"
				: "Schema 召回完成:命中 " + count + " 张表(" + String.join(", ", names) + ")";
	}

}
