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

	// —— 意图分类取值(意图识别节点的产出) ——

	/** 数据分析请求 */
	public static final String INTENT_DATA_ANALYSIS = "data_analysis";

	/** 闲聊 */
	public static final String INTENT_CHAT = "chat";

	private GraphKeys() {
	}

}
