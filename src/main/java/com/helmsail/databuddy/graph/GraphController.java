package com.helmsail.databuddy.graph;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 图入口(唯一 Controller):SSE 发起执行,另提供停止与线程记忆清理。
 * 只做 HTTP 层:建 sink、放行可推的帧、断连兜底停止;执行编排全在 GraphService
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

	/** 执行入口(SSE):GET /graph/run?agentId=…&input=…&sessionId=…(会话号可空,生成后随事件回传) */
	@GetMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphSseChunk>> run(@RequestParam("agentId") long agentId,
			@RequestParam("input") String input,
			@RequestParam(value = "sessionId", required = false) String sessionId,
			ServerHttpResponse response) {
		response.getHeaders().add("Cache-Control", "no-cache"); // SSE 不缓存
		Sinks.Many<ServerSentEvent<GraphSseChunk>> sink = Sinks.many().unicast().onBackpressureBuffer();
		String runId = graphService.stream(sink, agentId, input, sessionId);
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
	public ResponseEntity<Void> stop(@RequestParam(value = "runId", required = false) String runId,
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
		return ResponseEntity.noContent().build();
	}

	/** 清某线程键下的图侧记忆(客户端编排"删会话"时调用;图不解释该键含义) */
	@DeleteMapping("/memory")
	public ResponseEntity<Void> clearMemory(@RequestParam("sessionId") String sessionId) {
		graphService.clearMemory(sessionId);
		return ResponseEntity.noContent().build();
	}

}
