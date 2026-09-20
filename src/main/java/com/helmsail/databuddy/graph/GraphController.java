package com.helmsail.databuddy.graph;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.result.ApiResponse;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 图入口(唯一 Controller):SSE 发起执行与恢复,另提供停止与线程记忆清理。
 * 只做 HTTP 层:建 sink、放行可推的帧、断连兜底停止;执行编排全在 GraphService。
 * 通道边界:run/resume 为流式端点,流中错误走 error 帧、开流前的 HTTP 错误走全局处理器(信封体);
 * 其余端点与其他 JSON 端点一致,成功失败均为统一信封(见 ApiResponse)
 */
@Slf4j
@RestController
@RequestMapping("/graph")
@CrossOrigin(origins = "*")
public class GraphController {

	private final GraphService graphService;

	public GraphController(GraphService graphService) {
		this.graphService = graphService;
	}

	/** 执行入口(SSE):GET /graph/run?agentId=…&input=…&sessionId=…&humanReview=false(会话号可空,生成后随事件回传) */
	@GetMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphSseChunk>> run(@RequestParam("agentId") long agentId,
			@RequestParam("input") String input,
			@RequestParam(value = "sessionId", required = false) String sessionId,
			@RequestParam(value = "humanReview", required = false, defaultValue = "false") boolean humanReview,
			ServerHttpResponse response) {
		response.getHeaders().add("Cache-Control", "no-cache"); // SSE 不缓存
		Sinks.Many<ServerSentEvent<GraphSseChunk>> sink = Sinks.many().unicast().onBackpressureBuffer();
		String runId = graphService.stream(sink, agentId, input, sessionId, humanReview);
		return wire(sink, runId);
	}

	/** 恢复入口(SSE):GET /graph/resume?runId=…&approved=true|false&feedback=…(挂起轮的人工确认;新流接上断点续跑) */
	@GetMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphSseChunk>> resume(@RequestParam("runId") String runId,
			@RequestParam("approved") boolean approved,
			@RequestParam(value = "feedback", required = false) String feedback,
			ServerHttpResponse response) {
		response.getHeaders().add("Cache-Control", "no-cache");
		Sinks.Many<ServerSentEvent<GraphSseChunk>> sink = Sinks.many().unicast().onBackpressureBuffer();
		graphService.resume(sink, runId, approved, feedback);
		return wire(sink, runId);
	}

	/** SSE 管道公共接线:帧过滤(文本帧空文本不推,SQL 帧可重复推送)+ 断连/出错兜底停止 */
	private Flux<ServerSentEvent<GraphSseChunk>> wire(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink, String runId) {
		return sink.asFlux()
			// 只放行"有文本的文本帧"与协议帧
			.filter(sse -> {
				GraphSseChunk chunk = sse.data();
				if (!GraphKeys.TEXT.equals(chunk.getEventType())) {
					return true;
				}
				return StringUtils.hasText(chunk.getText());
			})
			.doOnCancel(() -> {
				log.info("客户端断开,停止执行: runId={}", runId);
				graphService.stop(runId);
			})
			.doOnError(error -> {
				log.error("SSE 管道出错: runId={}", runId, error);
				graphService.stop(runId);
			});
	}

	/** 停止:按运行号或会话号(二选一;都不带则参数错误) */
	@PostMapping("/stop")
	public ApiResponse<Void> stop(@RequestParam(value = "runId", required = false) String runId,
			@RequestParam(value = "sessionId", required = false) String sessionId) {
		if (StringUtils.hasText(runId)) {
			graphService.stop(runId);
		}
		else if (StringUtils.hasText(sessionId)) {
			graphService.stopBySession(sessionId);
		}
		else {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "停止必须携带 runId 或 sessionId");
		}
		return ApiResponse.success();
	}

	/** 清某线程键下的图侧记忆(客户端编排"删会话"时调用;图不解释该键含义) */
	@DeleteMapping("/memory")
	public ApiResponse<Void> clearMemory(@RequestParam("sessionId") String sessionId) {
		graphService.clearMemory(sessionId);
		return ApiResponse.success();
	}

}
