package com.helmsail.databuddy.exception;

import lombok.Getter;

/**
 * 业务异常:携带错误码,由全局异常处理器转换为对应 HTTP 响应
 */
public class BusinessException extends RuntimeException {

	/** 错误码 */
	@Getter
	private final ErrorCode errorCode;

	/** 使用错误码的默认文案 */
	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	/** 覆盖默认文案 */
	public BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	/** 覆盖默认文案,保留原始异常 */
	public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

}
