package com.helmsail.databuddy.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 错误码:统一管理错误的语义、默认文案与 HTTP 状态,新增错误只需加一行
 */
@Getter
public enum ErrorCode {

	/** 参数/业务规则不合法 → 400 */
	INVALID_INPUT("参数不合法", HttpStatus.BAD_REQUEST),

	/** 资源不存在 → 404 */
	NOT_FOUND("资源不存在", HttpStatus.NOT_FOUND),

	/** 系统内部错误 → 500 */
	SYSTEM_ERROR("服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

	private final String message;

	private final HttpStatus status;

	ErrorCode(String message, HttpStatus status) {
		this.message = message;
		this.status = status;
	}

}
