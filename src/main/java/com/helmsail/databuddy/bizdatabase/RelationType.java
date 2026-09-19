package com.helmsail.databuddy.bizdatabase;

import com.fasterxml.jackson.annotation.JsonCreator;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 关联数量关系(方向:source → target;如 MANY_TO_ONE = 源表多行对目标表一行)
 */
public enum RelationType {

	ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY;

	/** 从字符串解析(如 "one_to_many"),未知类型抛出业务异常;HTTP 请求体反序列化同样走这里 */
	@JsonCreator
	public static RelationType from(String type) {
		for (RelationType value : values()) {
			if (value.name().equalsIgnoreCase(type)) {
				return value;
			}
		}
		throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的关系类型: " + type);
	}

}
