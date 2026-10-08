package com.helmsail.databuddy.middle.graph.intent;

/**
 * 意图分类契约取值(与 intent-recognition 提示词输出一致:英文小写 data_analysis / chat;
 * 契约词表只此一处——提示词改词表时先动这里,非契约取值由节点带输出摘要抛错)
 */
enum IntentType {

	/** 可能的数据分析请求 */
	DATA_ANALYSIS,

	/** 闲聊或无关指令 */
	CHAT;

	/** 按 LLM 输出解析(大小写不敏感);非契约取值返回 null */
	static IntentType from(String value) {
		for (IntentType type : values()) {
			if (type.name().equalsIgnoreCase(value)) {
				return type;
			}
		}
		return null;
	}

}
