package com.helmsail.databuddy.graph;

/**
 * 图常量唯一登记:SSE 帧类型、节点 ID、状态键与节点取值
 */
public final class GraphKeys {

	// —— SSE 帧类型(对外契约;取值即 SSE 的 event 名) ——

	/** 文本帧:流式片段或整段文本 */
	public static final String TEXT = "text";

	/** 完成帧:本次执行正常结束 */
	public static final String DONE = "done";

	/** 错误帧:本次执行失败 */
	public static final String ERROR = "error";

	/** 过程帧:节点完成的轻量播报(结构化事件,与正文分离;对齐 AG-UI STEP / Dify node_finished) */
	public static final String STEP = "step";

	/** 计划帧:待确认的执行计划(text = 计划 JSON;人工确认闸挂起时下发) */
	public static final String PLAN = "plan";

	/** SQL 帧:新生成的 SQL 文本(text = SQL;去重后按需下发) */
	public static final String SQL = "sql";

	/** 结果帧:SQL 执行结果(text = 结果 JSON:{step,sql,columns,rows,row_count,truncated}) */
	public static final String RESULT = "result";

	// —— 节点 ID(与 node_prompt_template.name 对齐) ——

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

	// —— 状态键(OverAllState;随节点接入按需增补) ——

	/** 本轮输入 */
	public static final String INPUT = "input";

	/** 本轮对话的 agent(数据链身份轴;入口校验后注入) */
	public static final String AGENT_ID = "agent_id";

	/** 上文(进图前由记忆构建注入) */
	public static final String HISTORY = "history";

	/** 最终回复(END 输出的全量状态中提取) */
	public static final String FINAL_ANSWER = "final_answer";

	/** 意图分类结果:data_analysis / chat(意图识别产出;IntentRecognitionDispatcher 据此分流) */
	public static final String CLASSIFICATION = "classification";

	/** 召回的业务知识文本(术语/问答/文档,带来源标注;无命中为"无") */
	public static final String KNOWLEDGE = "knowledge";

	/** 规范查询:业务翻译后的完整查询(指代消解、绝对时间、术语已解析;回退时为原问题) */
	public static final String CANONICAL_QUERY = "canonical_query";

	/** 扩展问法列表(供下游检索;回退时为空表) */
	public static final String EXPANDED_QUERIES = "expanded_queries";

	/** 召回的表结构文本(表块内容拼接;未命中为"无") */
	public static final String SCHEMA = "schema";

	/** 召回的表名列表(表关系补拉后为最终可用表集;未命中为空表) */
	public static final String RECALLED_TABLES = "recalled_tables";

	/** 表关系清单文本(join 条件,每行一条;无关系为"无") */
	public static final String TABLE_RELATIONS = "table_relations";

	/** 节点过程状态:人类可读一句话,由 GraphService 转成 step 帧(不写则不播) */
	public static final String NODE_STATUS = "node_status";

	/** 执行计划 JSON(规划节点产出;提示词契约见 planner 种子) */
	public static final String PLAN_JSON = "plan_json";

	/** 当前步骤号(1 起;枢纽读,SQL 执行成功 / Python 分析完成时 +1) */
	public static final String PLAN_STEP = "plan_step";

	/** 人工确认开关(入口传参,默认关;挂起恢复后由确认节点关掉) */
	public static final String PLAN_REVIEW_ENABLED = "plan_review_enabled";

	/** 人工确认决定(恢复时由 updateState 写入:{approved, feedback}) */
	public static final String PLAN_REVIEW_DECISION = "plan_review_decision";

	/** 计划重写计数(校验失败 / 人工否决 / 执行组超限升级,共用;上限见各节点) */
	public static final String PLAN_REPAIR_COUNT = "plan_repair_count";

	/** 计划重写原因(注入规划提示词;失败路径每次覆盖写) */
	public static final String PLAN_REPAIR_REASON = "plan_repair_reason";

	/** 计划校验结果(枢纽写,分流器读) */
	public static final String PLAN_VALID = "plan_valid";

	/** 枢纽派活目标(枢纽/确认节点写,分流器读):节点 ID 或 END 标记 */
	public static final String PLAN_NEXT = "plan_next";

	/** 轻档开关(NL2SQL 模式:MCP 入口传参;规划固定单步不调 LLM,走完跳过报告) */
	public static final String NL2SQL_MODE = "nl2sql_mode";

	/** 当前生成的 SQL 文本(SQL 生成节点写;执行节点读) */
	public static final String SQL_QUERY = "sql_query";

	/** SQL 组尝试计数(生成即 +1;执行成功清零;超限触发升级) */
	public static final String SQL_ATTEMPT = "sql_attempt";

	/** SQL 组去向标记(组内节点写,分流器读):semantic / regenerate / replan / end / hub */
	public static final String SQL_NEXT = "sql_next";

	/** SQL 打回原因(语义不过 / 执行失败;生成成功时清空) */
	public static final String SQL_REPAIR_REASON = "sql_repair_reason";

	/** 最近一次 SQL 执行结果 JSON(执行节点写;Python 执行节点据此组装 input.json) */
	public static final String SQL_RESULT = "sql_result";

	/** 语义一致性结果(校验节点写,分流器读) */
	public static final String SEMANTIC_PASSED = "semantic_passed";

	/** 语义一致性未通过原因 */
	public static final String SEMANTIC_REASON = "semantic_reason";

	/** 当前 Python 代码(生成节点写;执行节点读) */
	public static final String PYTHON_CODE = "python_code";

	/** Python 组尝试计数(生成即 +1;分析完成清零;超限触发升级) */
	public static final String PYTHON_ATTEMPT = "python_attempt";

	/** Python 组去向标记(组内节点写,分流器读):analyze / regenerate / replan / end */
	public static final String PYTHON_NEXT = "python_next";

	/** Python 执行是否失败(Boolean) */
	public static final String PYTHON_FAILED = "python_failed";

	/** Python 失败原因(执行失败/超时/无产出;注入重写提示词) */
	public static final String PYTHON_FAIL_REASON = "python_fail_reason";

	/** Python 标准输出(stdout,约定的 JSON 结果) */
	public static final String PYTHON_RESULT = "python_result";

	/** Python 产物清单文本(/work/output 下文件名与大小;无产物为"无") */
	public static final String PYTHON_FILES = "python_files";

	/** Python 结果分析文本(分析节点写;报告节点引用) */
	public static final String PYTHON_ANALYSIS = "python_analysis";

	/** 分步结果累积(Map<String,String>;step_N = 结果 JSON,step_N_analysis = 分析文本) */
	public static final String STEP_RESULTS = "step_results";

	// —— 意图分类取值(意图识别节点的产出) ——

	/** 数据分析请求 */
	public static final String INTENT_DATA_ANALYSIS = "data_analysis";

	/** 闲聊 */
	public static final String INTENT_CHAT = "chat";

	private GraphKeys() {
	}

}
