package com.helmsail.databuddy.result;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * API 统一响应信封(JSON 通道唯一契约,成功与失败同形):
 * <pre>
 * 成功 2xx    {"success": true,  "message": null,       "data": &lt;payload&gt;}   // 无返回体的操作 data 为 null
 * 失败 4xx/5xx {"success": false, "message": "可读原因", "data": null}
 * </pre>
 * 约定(规范定稿,新增端点一律遵守):
 * 1) HTTP 状态码严格语义化(不搞"恒 200");success 与状态码恒一致,供前端做统一判别位;
 * 2) 所有 JSON 端点(含文件上传)一律包信封;分页响应的 data 为标准分页载荷 {@link PageResult};
 * 3) 失败只由全局异常处理器(GlobalExceptionHandler)产出,业务代码只抛 BusinessException,不手搓失败体;
 * 4) 通道边界:SSE(/graph/run、/graph/resume)走帧契约、MCP(/mcp)走协议自有形状,均不套本信封;
 *    唯 SSE 端点开流前的 HTTP 错误体仍为本信封形状;
 * 5) 错误码演进位:暂不加 code/traceId 字段(三码 + message 够用),将来加字段向后兼容。
 *
 * 前端对接(一次封装全覆盖):
 * <pre>
 * const j = await res.json(); if (!j.success) throw new Error(j.message); return j.data;
 * </pre>
 */
@Data
@NoArgsConstructor
public class ApiResponse<T> {

	private boolean success;

	private String message;

	private T data;

	public ApiResponse(boolean success, String message, T data) {
		this.success = success;
		this.message = message;
		this.data = data;
	}

	/** 成功(无返回体的操作:删除 / 触发类) */
	public static ApiResponse<Void> success() {
		return new ApiResponse<>(true, null, null);
	}

	/** 成功(纯数据,message 缺省为 null) */
	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(true, null, data);
	}

	/** 成功(带提示语的成功) */
	public static <T> ApiResponse<T> success(String message, T data) {
		return new ApiResponse<>(true, message, data);
	}

	/** 失败(仅全局异常处理器使用;业务代码请抛 BusinessException) */
	public static <T> ApiResponse<T> error(String message) {
		return new ApiResponse<>(false, message, null);
	}

}
