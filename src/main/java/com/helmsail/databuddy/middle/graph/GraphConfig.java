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
import com.helmsail.databuddy.middle.graph.enhance.EnhanceConstants;
import com.helmsail.databuddy.middle.graph.enhance.QueryEnhanceNode;
import com.helmsail.databuddy.middle.graph.feasibility.FeasibilityAssessmentDispatcher;
import com.helmsail.databuddy.middle.graph.feasibility.FeasibilityAssessmentNode;
import com.helmsail.databuddy.middle.graph.feasibility.FeasibilityConstants;
import com.helmsail.databuddy.middle.graph.intent.IntentConstants;
import com.helmsail.databuddy.middle.graph.intent.IntentRecognitionDispatcher;
import com.helmsail.databuddy.middle.graph.intent.IntentRecognitionNode;
import com.helmsail.databuddy.middle.graph.knowledge.KnowledgeConstants;
import com.helmsail.databuddy.middle.graph.knowledge.KnowledgeRecallNode;
import com.helmsail.databuddy.middle.graph.plan.PlanExecutorDispatcher;
import com.helmsail.databuddy.middle.graph.plan.PlanExecutorNode;
import com.helmsail.databuddy.middle.graph.plan.PlanConstants;
import com.helmsail.databuddy.middle.graph.plan.PlannerDispatcher;
import com.helmsail.databuddy.middle.graph.plan.PlannerNode;
import com.helmsail.databuddy.middle.graph.python.PythonAnalyzeDispatcher;
import com.helmsail.databuddy.middle.graph.python.PythonAnalyzeNode;
import com.helmsail.databuddy.middle.graph.python.PythonExecuteDispatcher;
import com.helmsail.databuddy.middle.graph.python.PythonExecuteNode;
import com.helmsail.databuddy.middle.graph.python.PythonGenerateDispatcher;
import com.helmsail.databuddy.middle.graph.python.PythonGenerateNode;
import com.helmsail.databuddy.middle.graph.python.PythonConstants;
import com.helmsail.databuddy.middle.graph.relation.RelationConstants;
import com.helmsail.databuddy.middle.graph.relation.TableRelationNode;
import com.helmsail.databuddy.middle.graph.report.ReportGeneratorNode;
import com.helmsail.databuddy.middle.graph.report.ReportConstants;
import com.helmsail.databuddy.middle.graph.review.PlanReviewDispatcher;
import com.helmsail.databuddy.middle.graph.review.PlanReviewNode;
import com.helmsail.databuddy.middle.graph.review.ReviewConstants;
import com.helmsail.databuddy.middle.graph.schema.SchemaConstants;
import com.helmsail.databuddy.middle.graph.schema.SchemaRecallDispatcher;
import com.helmsail.databuddy.middle.graph.schema.SchemaRecallNode;
import com.helmsail.databuddy.middle.graph.sql.SqlExecuteDispatcher;
import com.helmsail.databuddy.middle.graph.sql.SqlExecuteNode;
import com.helmsail.databuddy.middle.graph.sql.SqlGenerateDispatcher;
import com.helmsail.databuddy.middle.graph.sql.SqlGenerateNode;
import com.helmsail.databuddy.middle.graph.sql.SqlConstants;
import com.helmsail.databuddy.middle.graph.sql.SqlAnalyzeDispatcher;
import com.helmsail.databuddy.middle.graph.sql.SqlAnalyzeNode;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 图装配(全链版):拓扑、状态键策略、检查点、人工确认中断点——"怎么把图拼出来"都在这里;
 * 执行编排(跑图/事件/运行表/停止/释放/挂起恢复)在 GraphService。
 * 拓扑:入口 → 意图识别 →(chat)终点 /(data_analysis)知识召回 → 查询增强 → Schema 召回 → 表关系 →
 * 可行性评估 →(澄清)终点 /(可分析)规划(生成侧自校验,不过原地重生成,超限终止) →【人工确认闸,开关默认关】→ 计划执行(枢纽):
 * SQL 组(SQL 生成 → SQL 分析 → SQL 执行)与 Python 组(Python 生成 → Python 执行 → Python 分析)——
 * 两组同构,唯闸的排位不同(SQL 执行前审文本 / Python 执行后审数据);失败带原因打回生成,超限在生成口统一升级回规划(全局 ≤ 3);
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
			SqlGenerateNode sqlGenerateNode, SqlAnalyzeNode sqlAnalyzeNode, SqlExecuteNode sqlExecuteNode,
			PythonGenerateNode pythonGenerateNode, PythonExecuteNode pythonExecuteNode, PythonAnalyzeNode pythonAnalyzeNode,
			ReportGeneratorNode reportGeneratorNode) throws GraphStateException {
		// 状态键已超 Map.of 的十对上限,用 ofEntries 表达
		KeyStrategyFactory keyStrategyFactory = () -> Map.ofEntries(
				Map.entry(GraphKeys.Info.INPUT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.AGENT_ID, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.SESSION_MEMORY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.AGENT_MEMORY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.FINAL_ANSWER, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.INTENT_CLASSIFICATION, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.KNOWLEDGE, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.MAIN_QUERY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.BACKUP_QUERIES, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.SCHEMA, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.RECALLED_TABLES, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.TABLE_RELATIONS, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.PROGRESS, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.PLAN_JSON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PLAN_STEP_NO, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PLAN_REVIEW_ENABLED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PLAN_REVIEW_DECISION, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PLAN_RETRY_COUNT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PLAN_REPAIR_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PLAN_NEXT_NODE, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.NL2SQL_ENABLED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.SQL_QUERY, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.SQL_RETRY_COUNT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.SQL_NEXT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.SQL_REPAIR_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.SQL_RESULT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.SQL_PASSED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.PYTHON_CODE, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PYTHON_RETRY_COUNT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PYTHON_NEXT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PYTHON_REPAIR_REASON, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Control.PYTHON_PASSED, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.PYTHON_RESULT, KeyStrategy.REPLACE),
				Map.entry(GraphKeys.Info.STEP_RESULTS, KeyStrategy.REPLACE));
		return new StateGraph("databuddy", keyStrategyFactory)
			// 拓扑:入口 → 意图识别 → 按分类分流(chat → 终点;data_analysis → 知识召回)
			.addNode(IntentConstants.INTENT_RECOGNITION, intentRecognitionNode)
			.addNode(KnowledgeConstants.KNOWLEDGE_RECALL, knowledgeRecallNode)
			.addNode(EnhanceConstants.QUERY_ENHANCE, queryEnhanceNode)
			.addNode(SchemaConstants.SCHEMA_RECALL, schemaRecallNode)
			.addNode(RelationConstants.TABLE_RELATION, tableRelationNode)
			.addNode(FeasibilityConstants.FEASIBILITY_ASSESSMENT, feasibilityAssessmentNode)
			.addNode(PlanConstants.PLANNER, plannerNode)
			.addNode(ReviewConstants.PLAN_REVIEW, planReviewNode)
			.addNode(PlanConstants.PLAN_EXECUTOR, planExecutorNode)
			.addNode(SqlConstants.SQL_GENERATE, sqlGenerateNode)
			.addNode(SqlConstants.SQL_ANALYZE, sqlAnalyzeNode)
			.addNode(SqlConstants.SQL_EXECUTE, sqlExecuteNode)
			.addNode(PythonConstants.PYTHON_GENERATE, pythonGenerateNode)
			.addNode(PythonConstants.PYTHON_EXECUTE, pythonExecuteNode)
			.addNode(PythonConstants.PYTHON_ANALYZE, pythonAnalyzeNode)
			.addNode(ReportConstants.REPORT_GENERATOR, reportGeneratorNode)
			.addEdge(START, IntentConstants.INTENT_RECOGNITION)
			// 分流逻辑在 IntentRecognitionDispatcher(与节点同包);表声明可能去向(分流器直接返回目标,恒等映射)
			.addConditionalEdges(IntentConstants.INTENT_RECOGNITION,
					AsyncEdgeAction.edge_async(new IntentRecognitionDispatcher()),
					Map.of(END, END, KnowledgeConstants.KNOWLEDGE_RECALL, KnowledgeConstants.KNOWLEDGE_RECALL))
			// 知识召回 → 查询增强 → Schema 召回(直连)
			.addEdge(KnowledgeConstants.KNOWLEDGE_RECALL, EnhanceConstants.QUERY_ENHANCE)
			.addEdge(EnhanceConstants.QUERY_ENHANCE, SchemaConstants.SCHEMA_RECALL)
			// 分流逻辑在 SchemaRecallDispatcher:命中 → 表关系;未命中(已写终止语)→ 终点
			.addConditionalEdges(SchemaConstants.SCHEMA_RECALL,
					AsyncEdgeAction.edge_async(new SchemaRecallDispatcher()),
					Map.of(END, END, RelationConstants.TABLE_RELATION, RelationConstants.TABLE_RELATION))
			// 表关系 → 可行性评估 →(澄清→终点 / 可分析→规划)
			.addEdge(RelationConstants.TABLE_RELATION, FeasibilityConstants.FEASIBILITY_ASSESSMENT)
			.addConditionalEdges(FeasibilityConstants.FEASIBILITY_ASSESSMENT,
					AsyncEdgeAction.edge_async(new FeasibilityAssessmentDispatcher()),
					Map.of(END, END, PlanConstants.PLANNER, PlanConstants.PLANNER))
			// 规划 →(终止语)终点 /(过厂)枢纽;自校验在节点内循环,不走图;人工确认闸由枢纽按入口开关派发
			.addConditionalEdges(PlanConstants.PLANNER,
					AsyncEdgeAction.edge_async(new PlannerDispatcher()),
					Map.of(END, END, PlanConstants.PLAN_EXECUTOR, PlanConstants.PLAN_EXECUTOR))
			// 枢纽派活:确认闸 / SQL 组 / Python 组 / 报告 / 终点(轻档收束);每次必写 PLAN_NEXT_NODE,不在本表列规划
			.addConditionalEdges(PlanConstants.PLAN_EXECUTOR,
					AsyncEdgeAction.edge_async(new PlanExecutorDispatcher()),
					Map.of(END, END, ReviewConstants.PLAN_REVIEW, ReviewConstants.PLAN_REVIEW, SqlConstants.SQL_GENERATE,
							SqlConstants.SQL_GENERATE, PythonConstants.PYTHON_GENERATE, PythonConstants.PYTHON_GENERATE,
							ReportConstants.REPORT_GENERATOR, ReportConstants.REPORT_GENERATOR))
			// 人工确认闸(interruptBefore 静态中断点;开关关闭时枢纽不派向它,永不触发)
			.addConditionalEdges(ReviewConstants.PLAN_REVIEW,
					AsyncEdgeAction.edge_async(new PlanReviewDispatcher()),
					Map.of(END, END, PlanConstants.PLANNER, PlanConstants.PLANNER, PlanConstants.PLAN_EXECUTOR,
							PlanConstants.PLAN_EXECUTOR, ReviewConstants.PLAN_REVIEW, ReviewConstants.PLAN_REVIEW))
			// SQL 组:生成 → 分析(闸) → 执行;失败带原因打回生成,超限在生成口统一升级回规划
			.addConditionalEdges(SqlConstants.SQL_GENERATE,
					AsyncEdgeAction.edge_async(new SqlGenerateDispatcher()),
					Map.of(END, END, PlanConstants.PLANNER, PlanConstants.PLANNER, SqlConstants.SQL_GENERATE, SqlConstants.SQL_GENERATE,
							SqlConstants.SQL_ANALYZE, SqlConstants.SQL_ANALYZE))
			.addConditionalEdges(SqlConstants.SQL_ANALYZE,
					AsyncEdgeAction.edge_async(new SqlAnalyzeDispatcher()),
					Map.of(SqlConstants.SQL_EXECUTE, SqlConstants.SQL_EXECUTE, SqlConstants.SQL_GENERATE, SqlConstants.SQL_GENERATE))
			.addConditionalEdges(SqlConstants.SQL_EXECUTE,
					AsyncEdgeAction.edge_async(new SqlExecuteDispatcher()),
					Map.of(END, END, PlanConstants.PLAN_EXECUTOR, PlanConstants.PLAN_EXECUTOR, SqlConstants.SQL_GENERATE,
							SqlConstants.SQL_GENERATE))
			// Python 组:生成 → 执行 → 分析(闸);失败带原因打回生成,超限在生成口统一升级回规划
			.addConditionalEdges(PythonConstants.PYTHON_GENERATE,
					AsyncEdgeAction.edge_async(new PythonGenerateDispatcher()),
					Map.of(END, END, PlanConstants.PLANNER, PlanConstants.PLANNER, PythonConstants.PYTHON_EXECUTE,
							PythonConstants.PYTHON_EXECUTE))
			.addConditionalEdges(PythonConstants.PYTHON_EXECUTE,
					AsyncEdgeAction.edge_async(new PythonExecuteDispatcher()),
					Map.of(END, END, PythonConstants.PYTHON_GENERATE, PythonConstants.PYTHON_GENERATE,
							PythonConstants.PYTHON_ANALYZE, PythonConstants.PYTHON_ANALYZE))
			.addConditionalEdges(PythonConstants.PYTHON_ANALYZE,
					AsyncEdgeAction.edge_async(new PythonAnalyzeDispatcher()),
					Map.of(PlanConstants.PLAN_EXECUTOR, PlanConstants.PLAN_EXECUTOR, PythonConstants.PYTHON_GENERATE,
							PythonConstants.PYTHON_GENERATE))
			// 报告固定收尾
			.addEdge(ReportConstants.REPORT_GENERATOR, END)
			.compile(CompileConfig.builder()
				.saverConfig(SaverConfig.builder().register(checkpointSaver).build())
				// 人工确认闸:到达该节点前自动挂起(仅当枢纽按开关派向它时才会到达)
				.interruptBefore(ReviewConstants.PLAN_REVIEW)
				.build());
	}

}
