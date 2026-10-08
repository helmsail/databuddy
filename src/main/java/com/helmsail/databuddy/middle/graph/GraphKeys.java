package com.helmsail.databuddy.middle.graph;

/**
 * 状态键唯一登记(OverAllState;取值即状态键名,全仓 snake_case;不再按域分家)
 * (节点常量见各域 XxxConstants——节点 ID/契约取值/重试上限;SSE 帧类型见 GraphSseChunk)
 */
public final class GraphKeys {

	// —— 状态键(OverAllState;随节点接入按需增补) ——

	/** 本轮输入 */
	public static final String INPUT = "input";

	/** 本轮对话的 agent(数据链身份轴;入口校验后注入) */
	public static final String AGENT_ID = "agent_id";

	/** 会话记忆文本(进图前由 MemoryService 构建注入;含历史轮次与压缩摘要) */
	public static final String SESSION_MEMORY = "session_memory";

	/** 智能体沉淀记忆清单(进图前构建注入;每行"序号. [id=主键] 内容";无记忆为"(无)") */
	public static final String AGENT_MEMORY = "agent_memory";

	/** 最终回复(END 输出的全量状态中提取) */
	public static final String FINAL_ANSWER = "final_answer";

	/** 业务语义文本:智能体记忆(口径/规则/偏好)+ 召回知识(术语/问答/文档,带来源标注;两段皆空为"无") */
	public static final String KNOWLEDGE = "knowledge";

	/** 主查询:业务翻译后的完整查询(指代消解、绝对时间、术语已解析;回退时为原问题) */
	public static final String MAIN_QUERY = "main_query";

	/** 备用查询列表(查询增强产出;仅 Schema 召回在主路不足时补探多路;回退时为空表) */
	public static final String BACKUP_QUERIES = "backup_queries";

	/** 召回的表结构文本(表块内容拼接;未命中为"无") */
	public static final String SCHEMA = "schema";

	/** 召回的表名列表(表关系补拉后为最终可用表集;未命中为空表) */
	public static final String RECALLED_TABLES = "recalled_tables";

	/** 表关系清单文本(join 条件,每行一条;无关系为"无") */
	public static final String TABLE_RELATIONS = "table_relations";

	/** 过程播报文本(所有节点共用:每个节点完成时写一句人话,同键覆盖;GraphService 在每条节点输出时读它转 step 帧,不写则不播) */
	public static final String PROGRESS = "progress";

	/** 执行计划 JSON(规划节点产出;提示词契约见 planner 种子) */
	public static final String PLAN_JSON = "plan_json";

	/** 当前步号(数字,1 起;规划写 1,枢纽读,SQL 执行成功 / Python 分析完成时 +1) */
	public static final String PLAN_STEP_NO = "plan_step_no";

	/** 人工确认闸开关(入口传参,默认关;挂起恢复后由确认节点关掉) */
	public static final String HUMAN_REVIEW_ENABLED = "human_review_enabled";

	/** 人工确认决定(恢复时由 updateState 写入:{approved, feedback}) */
	public static final String PLAN_REVIEW_DECISION = "plan_review_decision";

	/** 计划重试计数(人工否决 / 执行组超限升级 共用;上限见各节点) */
	public static final String PLAN_RETRY_COUNT = "plan_retry_count";

	/** 计划重写原因(注入规划提示词;失败路径每次覆盖写) */
	public static final String PLAN_REPAIR_REASON = "plan_repair_reason";

	/** 下一跳节点(枢纽/确认节点写,分流器读):节点 ID 或 END 标记 */
	public static final String PLAN_NEXT_NODE = "plan_next_node";

	/** 轻档开关(NL2SQL 模式:MCP 入口传参;计划照常生成但只排 SQL 步,走完跳过报告) */
	public static final String NL2SQL_ENABLED = "nl2sql_enabled";

	/** 当前生成的 SQL 文本(SQL 生成节点写;执行节点读) */
	public static final String SQL_QUERY = "sql_query";

	/** 最近一次 SQL 执行结果 JSON(执行节点写;Python 执行节点据此组装 input.json) */
	public static final String SQL_RESULT = "sql_result";

	/** 分步结果累积(Map<String,String>;step_N = 结果文本(SQL 结果 JSON / Python stdout 及产出清单),step_N_analysis = 分析文本) */
	public static final String STEP_RESULTS = "step_results";

	// —— 意图域 ——

	/** 意图分类结果(取值见 intent 包 IntentConstants;IntentRecognitionDispatcher 据此分流) */
	public static final String CLASSIFICATION = "classification";

	// —— SQL 组 ——

	/** SQL 组重试计数(生成即 +1;执行成功清零;超限触发升级) */
	public static final String SQL_RETRY_COUNT = "sql_retry_count";

	/** SQL 组去向标记(组内节点写,分流器读):validate / regenerate / replan / end / hub */
	public static final String SQL_NEXT = "sql_next";

	/** SQL 打回原因(语义不过 / 执行失败;生成成功时清空) */
	public static final String SQL_REPAIR_REASON = "sql_repair_reason";

	/** SQL 校验结果(SQL 校验节点写,分流器读;未通过原因写 SQL_REPAIR_REASON 打回生成) */
	public static final String SQL_PASSED = "sql_passed";

	// —— Python 组 ——

	/** 当前 Python 代码(生成节点写;执行节点读) */
	public static final String PYTHON_CODE = "python_code";

	/** Python 组重试计数(生成即 +1;分析完成清零;超限触发升级) */
	public static final String PYTHON_RETRY_COUNT = "python_retry_count";

	/** Python 组去向标记(组内节点写,分流器读):analyze / regenerate / replan / end */
	public static final String PYTHON_NEXT = "python_next";

	/** Python 失败原因(执行失败/超时/无产出;注入重写提示词) */
	public static final String PYTHON_FAIL_REASON = "python_fail_reason";

	/** Python 标准输出(stdout,约定的 JSON 结果) */
	public static final String PYTHON_RESULT = "python_result";

	private GraphKeys() {
	}

}
