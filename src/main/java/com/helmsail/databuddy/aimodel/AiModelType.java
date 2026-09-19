package com.helmsail.databuddy.aimodel;

import com.fasterxml.jackson.annotation.JsonCreator;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 模型类型
 */
public enum AiModelType {

	CHAT, EMBEDDING;

	/** 从字符串解析(如外部配置传入 "chat"),未知类型抛出业务异常;HTTP 请求体反序列化同样走这里 */
	@JsonCreator
	public static AiModelType from(String type) {
		for (AiModelType value : values()) {
			if (value.name().equalsIgnoreCase(type)) {
				return value;
			}
		}
		throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的模型类型: " + type);
	}

}
