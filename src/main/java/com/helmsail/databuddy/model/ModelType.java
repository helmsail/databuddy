package com.helmsail.databuddy.model;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 模型类型
 */
public enum ModelType {

	CHAT, EMBEDDING;

	/** 从字符串解析(如外部配置传入 "chat"),未知类型抛出业务异常 */
	public static ModelType from(String type) {
		for (ModelType value : values()) {
			if (value.name().equalsIgnoreCase(type)) {
				return value;
			}
		}
		throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的模型类型: " + type);
	}

}
