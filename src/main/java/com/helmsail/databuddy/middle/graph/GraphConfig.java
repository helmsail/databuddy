package com.helmsail.databuddy.middle.graph;

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
import com.helmsail.databuddy.middle.graph.enhance.QueryEnhanceNode;
import com.helmsail.databuddy.middle.graph.feasibility.FeasibilityAssessmentDispatcher;
import com.helmsail.databuddy.middle.graph.feasibility.FeasibilityAssessmentNode;
import com.helmsail.databuddy.middle.graph.intent.IntentKeys;
import com.helmsail.databuddy.middle.graph.intent.IntentRecognitionDispatcher;
import com.helmsail.databuddy.middle.graph.intent.IntentRecognitionNode;
import com.helmsail.databuddy.middle.graph.knowledge.KnowledgeRecallNode;
import com.helmsail.databuddy.middle.graph.plan.PlanExecutorDispatcher;
import com.helmsail.databuddy.middle.graph.plan.PlanExecutorNode;
import com.helmsail.databuddy.middle.graph.plan.PlannerDispatcher;
import com.helmsail.databuddy.middle.graph.plan.PlannerNode;
import com.helmsail.databuddy.middle.graph.python.PythonAnalyzeNode;
import com.helmsail.databuddy.middle.graph.python.PythonExecuteDispatcher;
import com.helmsail.databuddy.middle.graph.python.PythonExecuteNode;
import com.helmsail.databuddy.middle.graph.python.PythonGenerateNode;
import com.helmsail.databuddy.middle.graph.python.PythonKeys;
import com.helmsail.databuddy.middle.graph.relation.TableRelationNode;
import com.helmsail.databuddy.middle.graph.report.ReportGeneratorNode;
import com.helmsail.databuddy.middle.graph.review.PlanReviewDispatcher;
import com.helmsail.databuddy.middle.graph.review.PlanReviewNode;
import com.helmsail.databuddy.middle.graph.schema.SchemaRecallDispatcher;
import com.helmsail.databuddy.middle.graph.schema.SchemaRecallNode;
import com.helmsail.databuddy.middle.graph.sql.SemanticConsistencyDispatcher;
import com.helmsail.databuddy.middle.graph.sql.SemanticConsistencyNode;
import com.helmsail.databuddy.middle.graph.sql.SqlExecuteDispatcher;
import com.helmsail.databuddy.middle.graph.sql.SqlExecuteNode;
import com.helmsail.databuddy.middle.graph.sql.SqlGenerateDispatcher;
import com.helmsail.databuddy.middle.graph.sql.SqlGenerateNode;
import com.helmsail.databuddy.middle.graph.sql.SqlKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 图装配(全链版):拓扑、状态键策略、检查点、人工确认中断点——"怎么把图拼出来"都在这里;
 * 执行编排(跑图/事件/运行表/停止/释放/挂起恢复)在 GraphService。
 * 拓扑:入口 → 意图识别 →(chat)终点 /(data_analysis)知识召回 → 查询增强 → Schema 召回 → 表关系 →
 * 可行性评估 →(澄清)终点 /(可分析)规划(生成侧自校验,不过原地重生成,超限终止) →【人工确认闸,开关默认关】→ 计划执行(枢纽):
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
				Map.entry(GraphKeys.SESSION_MEMORY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.AGENT_MEMORY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.FINAL_ANSWER, KeyStrategy.REPLACE),
				Map.entry(IntentKeys.CLASSIFICATION, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.KNOWLEDGE, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.MAIN_QUERY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.BACKUP_QUERIES, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SCHEMA, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.RECALLED_TABLES, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.TABLE_RELATIONS, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PROGRESS, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_JSON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_STEP_NO, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.HUMAN_REVIEW_ENABLED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_REVIEW_DECISION, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_REPAIR_COUNT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_REPAIR_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.PLAN_NEXT_NODE, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.NL2SQL_ENABLED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SQL_QUERY, KeyStrategy.REPLACE),
				Map.entry(SqlKeys.SQL_ATTEMPT, KeyStrategy.REPLACE),
				Map.entry(SqlKeys.SQL_NEXT, KeyStrategy.REPLACE),
				Map.entry(SqlKeys.SQL_REPAIR_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.SQL_RESULT, KeyStrategy.REPLACE),
				Map.entry(SqlKeys.SEMANTIC_PASSED, KeyStrategy.REPLACE),
				Map.entry(PythonKeys.PYTHON_CODE, KeyStrategy.REPLACE),
				Map.entry(PythonKeys.PYTHON_ATTEMPT, KeyStrategy.REPLACE),
				Map.entry(PythonKeys.PYTHON_NEXT, KeyStrategy.REPLACE),
				Map.entry(PythonKeys.PYTHON_FAIL_REASON, KeyStrategy.REPLACE),
				Map.entry(PythonKeys.PYTHON_RESULT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.STEP_RESULTS, KeyStrategy.REPLACE));
		return new StateGraph("databuddy", keyStrategyFactory)
			// 拓扑:入口 → 意图识别 → 按分类分流(chat → 终点;data_analysis → 知识召回)
			.addNode(GraphNodes.INTENT_RECOGNITION, intentRecognitionNode)
			.addNode(GraphNodes.KNOWLEDGE_RECALL, knowledgeRecallNode)
			.addNode(GraphNodes.QUERY_ENHANCE, queryEnhanceNode)
			.addNode(GraphNodes.SCHEMA_RECALL, schemaRecallNode)
			.addNode(GraphNodes.TABLE_RELATION, tableRelationNode)
			.addNode(GraphNodes.FEASIBILITY_ASSESSMENT, feasibilityAssessmentNode)
			.addNode(GraphNodes.PLANNER, plannerNode)
			.addNode(GraphNodes.PLAN_REVIEW, planReviewNode)
			.addNode(GraphNodes.PLAN_EXECUTOR, planExecutorNode)
			.addNode(GraphNodes.SQL_GENERATE, sqlGenerateNode)
			.addNode(GraphNodes.SEMANTIC_CONSISTENCY, semanticConsistencyNode)
			.addNode(GraphNodes.SQL_EXECUTE, sqlExecuteNode)
			.addNode(GraphNodes.PYTHON_GENERATE, pythonGenerateNode)
			.addNode(GraphNodes.PYTHON_EXECUTE, pythonExecuteNode)
			.addNode(GraphNodes.PYTHON_ANALYZE, pythonAnalyzeNode)
			.addNode(GraphNodes.REPORT_GENERATOR, reportGeneratorNode)
			.addEdge(START, GraphNodes.INTENT_RECOGNITION)
			// 分流逻辑在 IntentRecognitionDispatcher(与节点同包);表声明可能去向(分流器直接返回目标,恒等映射)
			.addConditionalEdges(GraphNodes.INTENT_RECOGNITION,
					AsyncEdgeAction.edge_async(new IntentRecognitionDispatcher()),
					Map.of(END, END, GraphNodes.KNOWLEDGE_RECALL, GraphNodes.KNOWLEDGE_RECALL))
			// 知识召回 → 查询增强 → Schema 召回(直连)
			.addEdge(GraphNodes.KNOWLEDGE_RECALL, GraphNodes.QUERY_ENHANCE)
			.addEdge(GraphNodes.QUERY_ENHANCE, GraphNodes.SCHEMA_RECALL)
			// 分流逻辑在 SchemaRecallDispatcher:命中 → 表关系;未命中(已写终止语)→ 终点
			.addConditionalEdges(GraphNodes.SCHEMA_RECALL,
					AsyncEdgeAction.edge_async(new SchemaRecallDispatcher()),
					Map.of(END, END, GraphNodes.TABLE_RELATION, GraphNodes.TABLE_RELATION))
			// 表关系 → 可行性评估 →(澄清→终点 / 可分析→规划)
			.addEdge(GraphNodes.TABLE_RELATION, GraphNodes.FEASIBILITY_ASSESSMENT)
			.addConditionalEdges(GraphNodes.FEASIBILITY_ASSESSMENT,
					AsyncEdgeAction.edge_async(new FeasibilityAssessmentDispatcher()),
					Map.of(END, END, GraphNodes.PLANNER, GraphNodes.PLANNER))
			// 规划 →(终止语)终点 /(过厂)枢纽;自校验在节点内循环,不走图;人工确认闸由枢纽按入口开关派发
			.addConditionalEdges(GraphNodes.PLANNER,
					AsyncEdgeAction.edge_async(new PlannerDispatcher()),
					Map.of(END, END, GraphNodes.PLAN_EXECUTOR, GraphNodes.PLAN_EXECUTOR))
			// 枢纽派活:确认闸 / SQL 组 / Python 组 / 报告 / 终点(轻档收束);每次必写 PLAN_NEXT_NODE,不在本表列规划
			.addConditionalEdges(GraphNodes.PLAN_EXECUTOR,
					AsyncEdgeAction.edge_async(new PlanExecutorDispatcher()),
					Map.of(END, END, GraphNodes.PLAN_REVIEW, GraphNodes.PLAN_REVIEW, GraphNodes.SQL_GENERATE,
							GraphNodes.SQL_GENERATE, GraphNodes.PYTHON_GENERATE, GraphNodes.PYTHON_GENERATE,
							GraphNodes.REPORT_GENERATOR, GraphNodes.REPORT_GENERATOR))
			// 人工确认闸(interruptBefore 静态中断点;开关关闭时枢纽不派向它,永不触发)
			.addConditionalEdges(GraphNodes.PLAN_REVIEW,
					AsyncEdgeAction.edge_async(new PlanReviewDispatcher()),
					Map.of(END, END, GraphNodes.PLANNER, GraphNodes.PLANNER, GraphNodes.PLAN_EXECUTOR,
							GraphNodes.PLAN_EXECUTOR, GraphNodes.PLAN_REVIEW, GraphNodes.PLAN_REVIEW))
			// SQL 组:生成 → 语义一致性 → 执行;失败带原因打回生成,超限升级回规划
			.addConditionalEdges(GraphNodes.SQL_GENERATE,
					AsyncEdgeAction.edge_async(new SqlGenerateDispatcher()),
					Map.of(END, END, GraphNodes.PLANNER, GraphNodes.PLANNER, GraphNodes.SQL_GENERATE, GraphNodes.SQL_GENERATE,
							GraphNodes.SEMANTIC_CONSISTENCY, GraphNodes.SEMANTIC_CONSISTENCY))
			.addConditionalEdges(GraphNodes.SEMANTIC_CONSISTENCY,
					AsyncEdgeAction.edge_async(new SemanticConsistencyDispatcher()),
					Map.of(GraphNodes.SQL_EXECUTE, GraphNodes.SQL_EXECUTE, GraphNodes.SQL_GENERATE, GraphNodes.SQL_GENERATE))
			.addConditionalEdges(GraphNodes.SQL_EXECUTE,
					AsyncEdgeAction.edge_async(new SqlExecuteDispatcher()),
					Map.of(END, END, GraphNodes.PLAN_EXECUTOR, GraphNodes.PLAN_EXECUTOR, GraphNodes.SQL_GENERATE,
							GraphNodes.SQL_GENERATE))
			// Python 组:生成 → 执行 → 分析;失败带原因打回生成,超限升级回规划
			.addEdge(GraphNodes.PYTHON_GENERATE, GraphNodes.PYTHON_EXECUTE)
			.addConditionalEdges(GraphNodes.PYTHON_EXECUTE,
					AsyncEdgeAction.edge_async(new PythonExecuteDispatcher()),
					Map.of(END, END, GraphNodes.PLANNER, GraphNodes.PLANNER, GraphNodes.PYTHON_GENERATE,
							GraphNodes.PYTHON_GENERATE, GraphNodes.PYTHON_ANALYZE, GraphNodes.PYTHON_ANALYZE))
			.addEdge(GraphNodes.PYTHON_ANALYZE, GraphNodes.PLAN_EXECUTOR)
			// 报告固定收尾
			.addEdge(GraphNodes.REPORT_GENERATOR, END)
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(checkpointSaver).build())
				// 人工确认闸:到达该节点前自动挂起(仅当枢纽按开关派向它时才会到达)
				.interruptBefore(GraphNodes.PLAN_REVIEW)
				.build());
	}

}
