package com.helmsail.databuddy.graph;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.helmsail.databuddy.graph.enhance.QueryEnhanceNode;
import com.helmsail.databuddy.graph.intent.IntentRecognitionDispatcher;
import com.helmsail.databuddy.graph.intent.IntentRecognitionNode;
import com.helmsail.databuddy.graph.knowledge.KnowledgeRecallNode;
import com.helmsail.databuddy.graph.relation.TableRelationNode;
import com.helmsail.databuddy.graph.schema.SchemaRecallDispatcher;
import com.helmsail.databuddy.graph.schema.SchemaRecallNode;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 图装配(骨架版):拓扑、状态键策略、检查点——"怎么把图拼出来"都在这里;
 * 执行编排(跑图/事件/运行表/停止/释放)在 GraphService。
 * 当前拓扑:入口 → 意图识别 →(chat)终点 /(data_analysis)知识召回 → 查询增强 → Schema 召回 → 表关系 → 终点(数据链后续节点接入时顺延)
 */
@Configuration
public class GraphConfig {

	/** 检查点:MySQL 存档(系统库);"跑完即释放"的时机由 GraphService 编排 */
	@Bean
	public BaseCheckpointSaver checkpointSaver(DataSource dataSource) {
		return MysqlSaver.builder().dataSource(dataSource).build();
	}

	@Bean
	public CompiledGraph databuddyGraph(BaseCheckpointSaver checkpointSaver, IntentRecognitionNode intentRecognitionNode,
			KnowledgeRecallNode knowledgeRecallNode, QueryEnhanceNode queryEnhanceNode, SchemaRecallNode schemaRecallNode,
			TableRelationNode tableRelationNode) throws GraphStateException {
		// 状态键已超 Map.of 的十对上限,用 ofEntries 表达
		KeyStrategyFactory keyStrategyFactory = () -> Map.ofEntries(
				Map.entry(GraphKeys.INPUT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.AGENT_ID, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.HISTORY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.FINAL_ANSWER, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.CLASSIFICATION, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.KNOWLEDGE, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.CANONICAL_QUERY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.EXPANDED_QUERIES, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SCHEMA, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.RECALLED_TABLES, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.TABLE_RELATIONS, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.NODE_STATUS, KeyStrategy.REPLACE));
		return new StateGraph("databuddy", keyStrategyFactory)
			// 拓扑:入口 → 意图识别 → 按分类分流(chat → 终点;data_analysis → 知识召回,数据链首节点)
			.addNode(GraphKeys.INTENT_RECOGNITION, intentRecognitionNode)
			.addNode(GraphKeys.KNOWLEDGE_RECALL, knowledgeRecallNode)
			.addNode(GraphKeys.QUERY_ENHANCE, queryEnhanceNode)
			.addNode(GraphKeys.SCHEMA_RECALL, schemaRecallNode)
			.addNode(GraphKeys.TABLE_RELATION, tableRelationNode)
			.addEdge(START, GraphKeys.INTENT_RECOGNITION)
			// 分流逻辑在 IntentRecognitionDispatcher(与节点同包);表声明可能去向(分流器直接返回目标,恒等映射)
			.addConditionalEdges(GraphKeys.INTENT_RECOGNITION,
					AsyncEdgeAction.edge_async(new IntentRecognitionDispatcher()),
					Map.of(END, END, GraphKeys.KNOWLEDGE_RECALL, GraphKeys.KNOWLEDGE_RECALL))
			// 知识召回 → 查询增强 → Schema 召回(直连)
			.addEdge(GraphKeys.KNOWLEDGE_RECALL, GraphKeys.QUERY_ENHANCE)
			.addEdge(GraphKeys.QUERY_ENHANCE, GraphKeys.SCHEMA_RECALL)
			// 分流逻辑在 SchemaRecallDispatcher(与节点同包):命中 → 表关系;未命中(已写终止语)→ 终点
			.addConditionalEdges(GraphKeys.SCHEMA_RECALL,
					AsyncEdgeAction.edge_async(new SchemaRecallDispatcher()),
					Map.of(END, END, GraphKeys.TABLE_RELATION, GraphKeys.TABLE_RELATION))
			// 表关系暂直连终点:数据链下一节点(可行性评估)接入时改指
			.addEdge(GraphKeys.TABLE_RELATION, END)
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(checkpointSaver).build())
				.build());
	}

}
