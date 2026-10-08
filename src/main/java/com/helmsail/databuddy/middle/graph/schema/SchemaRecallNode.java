package com.helmsail.databuddy.middle.graph.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.helmsail.databuddy.middle.biztable.AgentBizTableService;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.bottom.vectorize.RetrievedChunk;

import lombok.extern.slf4j.Slf4j;

/**
 * Schema 召回节点:数据链第三节点。用主查询 + 备用查询多路向量召回 agent 绑定的表块(按表名去重,块自足),
 * 拼接为 SCHEMA 文本、解析出表名写 RECALLED_TABLES;纯检索,不调 LLM。
 * 未命中:写终止语到 FINAL_ANSWER(经既有 END 机制播报给用户)与过程状态,流程收束;
 * 阻塞的检索调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class SchemaRecallNode implements AsyncNodeAction {

	/** 召回条数上限(整表一块,即最多召回的表的张数;偏宁多勿漏,关系补拉再兜底) */
	private static final int TOP_K = 8;

	/** 未命中终止语(用户可见) */
	private static final String NO_TABLE_MESSAGE = "未检索到与问题相关的数据表,本轮分析无法继续。"
			+ "可能原因:1) 智能体尚未绑定数据表;2) 表向量化未完成或失败;3) 问题与已绑定表的结构无关。";

	private final AgentBizTableService tableService;

	public SchemaRecallNode(AgentBizTableService tableService) {
		this.tableService = tableService;
	}
    
	@Override
	@Observed(name = "node.schemaRecall", contextualName = "Schema 召回")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String canonical = state.value(GraphKeys.MAIN_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		long agentId = NodeUtils.longOf(state, GraphKeys.AGENT_ID);
		List<String> queryList = queries(state, canonical);
		List<RetrievedChunk> tables = recall(agentId, queryList);
		if (tables.isEmpty()) {
			log.warn("Schema 召回未命中: agent={}, 查询=\"{}\"", agentId, canonical);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SCHEMA, "无", GraphKeys.RECALLED_TABLES, List.of(),
					GraphKeys.PROGRESS, "Schema 召回未命中:未检索到相关数据表", GraphKeys.FINAL_ANSWER, NO_TABLE_MESSAGE));
		}
		List<String> names = names(tables);
		log.info("Schema 召回: agent={}, {} 路查询命中 {} 张表: {}", agentId, queryList.size(), tables.size(), names);
		return CompletableFuture.completedFuture(Map.of(GraphKeys.SCHEMA, join(tables), GraphKeys.RECALLED_TABLES, names,
				GraphKeys.PROGRESS, note(tables.size(), names)));
	}

	/** 检索查询组:主查询 + 备用查询(去重,主查询优先;无备用时单路) */
	private List<String> queries(OverAllState state, String canonical) {
		List<String> queries = new ArrayList<>();
		queries.add(canonical);
		for (String expanded : NodeUtils.stringList(state, GraphKeys.BACKUP_QUERIES)) {
			if (!queries.contains(expanded)) {
				queries.add(expanded);
			}
		}
		return queries;
	}

	/** 多路召回并按表名归并(先到先得;总张数不超 TOP_K,已满不再消耗后续查询) */
	private List<RetrievedChunk> recall(long agentId, List<String> queries) {
		Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
		for (String query : queries) {
			for (RetrievedChunk table : tableService.retrieve(agentId, query, TOP_K)) {
				String name = NodeUtils.parseTableName(table.getContent());
				merged.putIfAbsent(name == null ? table.getContent() : name, table);
			}
			if (merged.size() >= TOP_K) {
				break;
			}
		}
		return merged.values().stream().limit(TOP_K).toList();
	}

	/** 各块内容拼接为 schema 文本(块自足:表名+注释+全列,零加工) */
	private String join(List<RetrievedChunk> tables) {
		StringBuilder schema = new StringBuilder();
		for (RetrievedChunk table : tables) {
			schema.append(table.getContent()).append('\n');
		}
		return schema.toString().trim();
	}

	/** 解析表名(约定在 NodeUtils.parseTableName;解析不到只是少个名字,流程判断用块数,不受影响) */
	private List<String> names(List<RetrievedChunk> tables) {
		List<String> names = new ArrayList<>(tables.size());
		for (RetrievedChunk table : tables) {
			String name = NodeUtils.parseTableName(table.getContent());
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
