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
import com.helmsail.databuddy.graph.feasibility.FeasibilityAssessmentDispatcher;
import com.helmsail.databuddy.graph.feasibility.FeasibilityAssessmentNode;
import com.helmsail.databuddy.graph.intent.IntentRecognitionDispatcher;
import com.helmsail.databuddy.graph.intent.IntentRecognitionNode;
import com.helmsail.databuddy.graph.knowledge.KnowledgeRecallNode;
import com.helmsail.databuddy.graph.plan.PlanExecutorDispatcher;
import com.helmsail.databuddy.graph.plan.PlanExecutorNode;
import com.helmsail.databuddy.graph.plan.PlannerNode;
import com.helmsail.databuddy.graph.python.PythonAnalyzeNode;
import com.helmsail.databuddy.graph.python.PythonExecuteDispatcher;
import com.helmsail.databuddy.graph.python.PythonExecuteNode;
import com.helmsail.databuddy.graph.python.PythonGenerateNode;
import com.helmsail.databuddy.graph.relation.TableRelationNode;
import com.helmsail.databuddy.graph.report.ReportGeneratorNode;
import com.helmsail.databuddy.graph.review.PlanReviewDispatcher;
import com.helmsail.databuddy.graph.review.PlanReviewNode;
import com.helmsail.databuddy.graph.schema.SchemaRecallDispatcher;
import com.helmsail.databuddy.graph.schema.SchemaRecallNode;
import com.helmsail.databuddy.graph.sql.SemanticConsistencyDispatcher;
import com.helmsail.databuddy.graph.sql.SemanticConsistencyNode;
import com.helmsail.databuddy.graph.sql.SqlExecuteDispatcher;
import com.helmsail.databuddy.graph.sql.SqlExecuteNode;
import com.helmsail.databuddy.graph.sql.SqlGenerateDispatcher;
import com.helmsail.databuddy.graph.sql.SqlGenerateNode;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 图装配(全链版):拓扑、状态键策略、检查点、人工确认中断点——"怎么把图拼出来"都在这里;
 * 执行编排(跑图/事件/运行表/停止/释放/挂起恢复)在 GraphService。
 * 拓扑:入口 → 意图识别 →(chat)终点 /(data_analysis)知识召回 → 查询增强 → Schema 召回 → 表关系 →
 * 可行性评估 →(澄清)终点 /(可分析)规划 →【人工确认闸,开关默认关】→ 计划执行(枢纽):
 * SQL 组(SQL 生成 → 语义一致性 → SQL 执行,失败带原因打回生成)与 Python 组
 * (Python 生成 → Python 执行 → Python 分析)回流枢纽,组内超限升级回规划(全局 ≤ 3);
 * 步数走完 → 报告生成(固定收尾)→ 终点
 */
@Configuration
public class GraphConfig {

	/** 检查点:MySQL 存档(系统库);"跑完即释放"的时机由 GraphService 编排(挂起轮是唯一保留例外) */
	@Bean
	public BaseCheckpointSaver checkpointSaver(DataSource dataSource) {
		return MysqlSaver.builder().dataSource(dataSource).build();
	}

	@Bean
	public CompiledGraph databuddyGraph(BaseCheckpointSaver checkpointSaver, IntentRecognitionNode intentRecognitionNode,
			KnowledgeRecallNode knowledgeRecallNode, QueryEnhanceNode queryEnhanceNode, SchemaRecallNode schemaRecallNode,
			TableRelationNode tableRelationNode, FeasibilityAssessmentNode feasibilityAssessmentNode,
			PlannerNode plannerNode, PlanReviewNode planReviewNode, PlanExecutorNode planExecutorNode,
			SqlGenerateNode sqlGenerateNode, SemanticConsistencyNode semanticConsistencyNode, SqlExecuteNode sqlExecuteNode,
			PythonGenerateNode pythonGenerateNode, PythonExecuteNode pythonExecuteNode, PythonAnalyzeNode pythonAnalyzeNode,
			ReportGeneratorNode reportGeneratorNode) throws GraphStateException {
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
				Map.entry(GraphKeys.NODE_STATUS, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_JSON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_STEP, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_REVIEW_ENABLED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_REVIEW_DECISION, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_REPAIR_COUNT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_REPAIR_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_VALID, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_NEXT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.NL2SQL_MODE, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SQL_QUERY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SQL_ATTEMPT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SQL_NEXT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SQL_REPAIR_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SQL_RESULT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SEMANTIC_PASSED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SEMANTIC_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PYTHON_CODE, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PYTHON_ATTEMPT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PYTHON_NEXT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PYTHON_FAILED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PYTHON_FAIL_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PYTHON_RESULT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PYTHON_FILES, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PYTHON_ANALYSIS, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.STEP_RESULTS, KeyStrategy.REPLACE));
		return new StateGraph("databuddy", keyStrategyFactory)
			// 拓扑:入口 → 意图识别 → 按分类分流(chat → 终点;data_analysis → 知识召回)
			.addNode(GraphKeys.INTENT_RECOGNITION, intentRecognitionNode)
			.addNode(GraphKeys.KNOWLEDGE_RECALL, knowledgeRecallNode)
			.addNode(GraphKeys.QUERY_ENHANCE, queryEnhanceNode)
			.addNode(GraphKeys.SCHEMA_RECALL, schemaRecallNode)
			.addNode(GraphKeys.TABLE_RELATION, tableRelationNode)
			.addNode(GraphKeys.FEASIBILITY_ASSESSMENT, feasibilityAssessmentNode)
			.addNode(GraphKeys.PLANNER, plannerNode)
			.addNode(GraphKeys.PLAN_REVIEW, planReviewNode)
			.addNode(GraphKeys.PLAN_EXECUTOR, planExecutorNode)
			.addNode(GraphKeys.SQL_GENERATE, sqlGenerateNode)
			.addNode(GraphKeys.SEMANTIC_CONSISTENCY, semanticConsistencyNode)
			.addNode(GraphKeys.SQL_EXECUTE, sqlExecuteNode)
			.addNode(GraphKeys.PYTHON_GENERATE, pythonGenerateNode)
			.addNode(GraphKeys.PYTHON_EXECUTE, pythonExecuteNode)
			.addNode(GraphKeys.PYTHON_ANALYZE, pythonAnalyzeNode)
			.addNode(GraphKeys.REPORT_GENERATOR, reportGeneratorNode)
			.addEdge(START, GraphKeys.INTENT_RECOGNITION)
			// 分流逻辑在 IntentRecognitionDispatcher(与节点同包);表声明可能去向(分流器直接返回目标,恒等映射)
			.addConditionalEdges(GraphKeys.INTENT_RECOGNITION,
					AsyncEdgeAction.edge_async(new IntentRecognitionDispatcher()),
					Map.of(END, END, GraphKeys.KNOWLEDGE_RECALL, GraphKeys.KNOWLEDGE_RECALL))
			// 知识召回 → 查询增强 → Schema 召回(直连)
			.addEdge(GraphKeys.KNOWLEDGE_RECALL, GraphKeys.QUERY_ENHANCE)
			.addEdge(GraphKeys.QUERY_ENHANCE, GraphKeys.SCHEMA_RECALL)
			// 分流逻辑在 SchemaRecallDispatcher:命中 → 表关系;未命中(已写终止语)→ 终点
			.addConditionalEdges(GraphKeys.SCHEMA_RECALL,
					AsyncEdgeAction.edge_async(new SchemaRecallDispatcher()),
					Map.of(END, END, GraphKeys.TABLE_RELATION, GraphKeys.TABLE_RELATION))
			// 表关系 → 可行性评估 →(澄清→终点 / 可分析→规划)
			.addEdge(GraphKeys.TABLE_RELATION, GraphKeys.FEASIBILITY_ASSESSMENT)
			.addConditionalEdges(GraphKeys.FEASIBILITY_ASSESSMENT,
					AsyncEdgeAction.edge_async(new FeasibilityAssessmentDispatcher()),
					Map.of(END, END, GraphKeys.PLANNER, GraphKeys.PLANNER))
			// 规划 → 计划执行(枢纽):人工确认闸由枢纽按入口开关派发,不在主线直连
			.addEdge(GraphKeys.PLANNER, GraphKeys.PLAN_EXECUTOR)
			// 枢纽派活:确认闸 / SQL 组 / Python 组 / 报告 / 回规划重写 / 终点(超限终止)
			.addConditionalEdges(GraphKeys.PLAN_EXECUTOR,
					AsyncEdgeAction.edge_async(new PlanExecutorDispatcher()),
					Map.of(END, END, GraphKeys.PLAN_REVIEW, GraphKeys.PLAN_REVIEW, GraphKeys.SQL_GENERATE,
							GraphKeys.SQL_GENERATE, GraphKeys.PYTHON_GENERATE, GraphKeys.PYTHON_GENERATE,
							GraphKeys.REPORT_GENERATOR, GraphKeys.REPORT_GENERATOR, GraphKeys.PLANNER, GraphKeys.PLANNER))
			// 人工确认闸(interruptBefore 静态中断点;开关关闭时枢纽不派向它,永不触发)
			.addConditionalEdges(GraphKeys.PLAN_REVIEW,
					AsyncEdgeAction.edge_async(new PlanReviewDispatcher()),
					Map.of(END, END, GraphKeys.PLANNER, GraphKeys.PLANNER, GraphKeys.PLAN_EXECUTOR,
							GraphKeys.PLAN_EXECUTOR, GraphKeys.PLAN_REVIEW, GraphKeys.PLAN_REVIEW))
			// SQL 组:生成 → 语义一致性 → 执行;失败带原因打回生成,超限升级回规划
			.addConditionalEdges(GraphKeys.SQL_GENERATE,
					AsyncEdgeAction.edge_async(new SqlGenerateDispatcher()),
					Map.of(END, END, GraphKeys.PLANNER, GraphKeys.PLANNER, GraphKeys.SQL_GENERATE, GraphKeys.SQL_GENERATE,
							GraphKeys.SEMANTIC_CONSISTENCY, GraphKeys.SEMANTIC_CONSISTENCY))
			.addConditionalEdges(GraphKeys.SEMANTIC_CONSISTENCY,
					AsyncEdgeAction.edge_async(new SemanticConsistencyDispatcher()),
					Map.of(GraphKeys.SQL_EXECUTE, GraphKeys.SQL_EXECUTE, GraphKeys.SQL_GENERATE, GraphKeys.SQL_GENERATE))
			.addConditionalEdges(GraphKeys.SQL_EXECUTE,
					AsyncEdgeAction.edge_async(new SqlExecuteDispatcher()),
					Map.of(END, END, GraphKeys.PLAN_EXECUTOR, GraphKeys.PLAN_EXECUTOR, GraphKeys.SQL_GENERATE,
							GraphKeys.SQL_GENERATE))
			// Python 组:生成 → 执行 → 分析;失败带原因打回生成,超限升级回规划
			.addEdge(GraphKeys.PYTHON_GENERATE, GraphKeys.PYTHON_EXECUTE)
			.addConditionalEdges(GraphKeys.PYTHON_EXECUTE,
					AsyncEdgeAction.edge_async(new PythonExecuteDispatcher()),
					Map.of(END, END, GraphKeys.PLANNER, GraphKeys.PLANNER, GraphKeys.PYTHON_GENERATE,
							GraphKeys.PYTHON_GENERATE, GraphKeys.PYTHON_ANALYZE, GraphKeys.PYTHON_ANALYZE))
			.addEdge(GraphKeys.PYTHON_ANALYZE, GraphKeys.PLAN_EXECUTOR)
			// 报告固定收尾
			.addEdge(GraphKeys.REPORT_GENERATOR, END)
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(checkpointSaver).build())
				// 人工确认闸:到达该节点前自动挂起(仅当枢纽按开关派向它时才会到达)
				.interruptBefore(GraphKeys.PLAN_REVIEW)
				.build());
	}

}
