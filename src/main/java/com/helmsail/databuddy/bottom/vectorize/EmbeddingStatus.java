package com.helmsail.databuddy.bottom.vectorize;

import com.fasterxml.jackson.annotation.JsonCreator;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 知识源向量化状态(agent_biz_*.embedding_status 存 name()):
 * PENDING 待向量化 / SYNCED 已同步 / FAILED 失败待重试。
 * 写入统一"声明 from → to":canTransitionTo 校验迁移对,updateSyncStatus 以 CAS(from 条件)落库;
 * 入队(新增 / 重置)→ PENDING;出队回执 → SYNCED / FAILED(失败留原因);重试面向非 SYNCED 集合;全量重建对任意态直刷
 */
public enum EmbeddingStatus {

	PENDING, SYNCED, FAILED;

	/**
	 * 状态机迁移表(当前态 → next):现有边的全集 = 全部 9 条(入队、回执、重试自环、重置、重建直刷均属预期),故当前恒真。
	 * 此为迁移约束的唯一检查点——将来收紧(如新增 SYNCING 中间态)只改这里
	 */
	public boolean canTransitionTo(EmbeddingStatus next) {
		if (next == null) {
			return false;
		}
		return switch (this) {
			case PENDING -> next == PENDING || next == SYNCED || next == FAILED;
			case FAILED -> next == PENDING || next == SYNCED || next == FAILED;
			case SYNCED -> next == PENDING || next == SYNCED || next == FAILED;
		};
	}

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
