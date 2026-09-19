package com.helmsail.databuddy.agent;

import com.fasterxml.jackson.annotation.JsonCreator;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 知识源向量化状态(agent_biz_*.embedding_status 存 name()):
 * PENDING 待向量化 / SYNCED 已同步 / FAILED 失败待重试
 */
public enum EmbeddingStatus {

	PENDING, SYNCED, FAILED;

	/** 从字符串解析(如 "pending"),未知类型抛出业务异常;HTTP 请求体反序列化同样走这里 */
	@JsonCreator
	public static EmbeddingStatus from(String status) {
		for (EmbeddingStatus value : values()) {
			if (value.name().equalsIgnoreCase(status)) {
				return value;
			}
		}
		throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的向量化状态: " + status);
	}

}
