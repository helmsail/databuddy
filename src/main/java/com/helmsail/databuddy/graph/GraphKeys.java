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

	// —— 节点 ID(与 node_prompt_template.name 对齐) ——

	/** 意图识别节点 */
	public static final String INTENT_RECOGNITION = "intent-recognition";

	// —— 状态键(OverAllState;随节点接入按需增补) ——

	/** 本轮输入 */
	public static final String INPUT = "input";

	/** 上文(进图前由记忆构建注入) */
	public static final String HISTORY = "history";

	/** 最终回复(END 输出的全量状态中提取) */
	public static final String FINAL_ANSWER = "final_answer";

	/** 意图分类结果:data_analysis / chat(将来条件边的映射键) */
	public static final String CLASSIFICATION = "classification";

	// —— 意图分类取值(意图识别节点的产出) ——

	/** 数据分析请求 */
	public static final String INTENT_DATA_ANALYSIS = "data_analysis";

	/** 闲聊 */
	public static final String INTENT_CHAT = "chat";

	private GraphKeys() {
	}

}
