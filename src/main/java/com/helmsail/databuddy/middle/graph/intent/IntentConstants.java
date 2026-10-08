package com.helmsail.databuddy.middle.graph.intent;

/**
 * 意图域常量(唯一来源):节点 ID 与分类取值
 * (classification 取值与 intent-recognition 提示词输出一致:英文小写 data_analysis / chat,非契约取值由节点带输出摘要抛错)
 */
public final class IntentConstants {

	/** 意图识别节点 */
	public static final String INTENT_RECOGNITION = "intent-recognition";

	/** 分类取值:可能的数据分析请求 */
	public static final String DATA_ANALYSIS = "data_analysis";

	/** 分类取值:闲聊或无关指令 */
	public static final String CHAT = "chat";

	private IntentConstants() {
	}

}
