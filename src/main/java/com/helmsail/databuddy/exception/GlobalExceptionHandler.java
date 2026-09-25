package com.helmsail.databuddy.exception;

import com.helmsail.databuddy.result.ApiResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理器:JSON 端点错误的唯一出口,统一输出 ApiResponse 信封(与成功路径同形,约定见 ApiResponse)。
 * 覆盖矩阵:业务异常(按 ErrorCode 状态)/ 数据完整性冲突(400)/ 框架响应状态异常(保原状态)/ 兜底(500 脱敏)。
 * 通道边界:图端点的校验/执行错误均走流内 error 帧(GraphService),不经过此处;
 * 此处兜其余端点与框架级错误(参数绑定等;流式客户端读不到信封,降级为通用提示)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** 业务异常 → ErrorCode 对应的 HTTP 状态 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
		ErrorCode errorCode = e.getErrorCode();
		log.warn("业务异常 [{}]: {}", errorCode, e.getMessage());
		return ResponseEntity.status(errorCode.getStatus()).body(ApiResponse.error(e.getMessage()));
	}

	/** 数据完整性冲突(唯一键重复等)→ 400;细节进日志,不外泄 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
		log.warn("数据冲突: {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
			.body(ApiResponse.error("数据冲突:已存在相同记录或违反数据约束"));
	}

	/** 响应状态异常(如 404、405)→ 保留原始状态码 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException e) {
		String message = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
		if (e.getStatusCode().is5xxServerError()) {
			log.error("请求失败,状态码 {}: {}", e.getStatusCode(), message, e);
		}
		else {
			log.warn("请求失败,状态码 {}: {}", e.getStatusCode(), message);
		}
		return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.error(message));
	}

	/** 兜底:未预期的异常 → 500,不向外暴露细节 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
		log.error("未预期异常", e);
		return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getStatus())
			.body(ApiResponse.error(ErrorCode.SYSTEM_ERROR.getMessage()));
	}

}
