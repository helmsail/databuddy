package com.helmsail.databuddy.middle.graph;

/**
 * 图节点 ID 唯一登记(与 node_prompt_template.name 对齐):
 * 注册节点、边的目标、路由值(如 plan_next_node 状态里存的跳转目标)三用一源;
 * 状态键与 SSE 帧类型见 GraphKeys
 */
public final class GraphNodes {

	/** 意图识别节点 */
	public static final String INTENT_RECOGNITION = "intent-recognition";

	/** 知识召回节点(数据链首节点) */
	public static final String KNOWLEDGE_RECALL = "knowledge-recall";

	/** 查询增强节点(数据链第二节点) */
	public static final String QUERY_ENHANCE = "query-enhance";

	/** Schema 召回节点(数据链第三节点) */
	public static final String SCHEMA_RECALL = "schema-recall";

	/** 表关系节点(数据链第四节点) */
	public static final String TABLE_RELATION = "table-relation";

	/** 可行性评估节点(数据链第五节点) */
	public static final String FEASIBILITY_ASSESSMENT = "feasibility-assessment";

	/** 规划节点(数据链第六节点):产出执行计划 */
	public static final String PLANNER = "planner";

	/** 人工确认闸:计划执行前的唯一拦截点(interruptBefore 静态中断;入口开关默认关) */
	public static final String PLAN_REVIEW = "plan-review";

	/** 计划执行节点(枢纽):按当前步派活,走完转报告 */
	public static final String PLAN_EXECUTOR = "plan-executor";

	/** SQL 生成节点(SQL 组头) */
	public static final String SQL_GENERATE = "sql-generate";

	/** 语义一致性节点(SQL 组质检,执行前审文本) */
	public static final String SEMANTIC_CONSISTENCY = "semantic-consistency";

	/** SQL 执行节点(对业务库运行只读查询) */
	public static final String SQL_EXECUTE = "sql-execute";

	/** Python 生成节点(Python 组头) */
	public static final String PYTHON_GENERATE = "python-generate";

	/** Python 执行节点(沙箱运行) */
	public static final String PYTHON_EXECUTE = "python-execute";

	/** Python 分析节点(Python 组质检,执行后审结果) */
	public static final String PYTHON_ANALYZE = "python-analyze";

	/** 报告生成节点(固定收尾) */
	public static final String REPORT_GENERATOR = "report-generator";

	private GraphNodes() {
	}

}
