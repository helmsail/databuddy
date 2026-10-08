package com.helmsail.databuddy.bottom.vectorize;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 知识类型(写入 metadata 的 knowledge_type;四类知识子域各一,回源时据它 + knowledge_id 定位)
 */
public enum KnowledgeType {

	TABLE, TERM, DOCUMENT, QA;

	/** 从字符串解析(如 "table"),未知类型抛出业务异常 */
	public static KnowledgeType from(String type) {
		for (KnowledgeType value : values()) {
			if (value.name().equalsIgnoreCase(type)) {
				return value;
			}
		}
		throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的知识类型: " + type);
	}

}
