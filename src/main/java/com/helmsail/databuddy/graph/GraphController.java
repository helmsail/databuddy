package com.helmsail.databuddy.graph;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.result.ApiResponse;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 图入口(唯一 Controller):SSE 发起执行与恢复,另提供停止与记忆清理。
 * 图线程键 = 会话键(一线程一会话;对接官方 threadId):HTTP 层发起/恢复/停止/清记忆全部以 sessionId 寻址。
 * 只做 HTTP 层:建 sink、放行可推的帧、断连兜底停止;执行编排全在 GraphService。
 * 通道边界:run/resume 的入口校验失败与执行期错误统一走流内 error 帧(EventSource 读不到 HTTP 信封);
 * 参数绑定等框架级错误走全局处理器(信封体)。阻塞边界:清记忆等阻塞活调度到弹性线程,不占事件循环
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

	/** 执行入口(SSE):GET /graph/run?agentId=…&input=…&sessionId=…&planReview=false(会话号缺省则生成,随事件回传) */
	@GetMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphSseChunk>> run(@RequestParam("agentId") long agentId,
			@RequestParam("input") String input,
			@RequestParam(value = "sessionId", required = false) String sessionId,
			@RequestParam(value = "planReview", required = false, defaultValue = "false") boolean planReview,
			ServerHttpResponse response) {
		noCache(response);
		Sinks.Many<ServerSentEvent<GraphSseChunk>> sink = Sinks.many().unicast().onBackpressureBuffer();
		String resolvedSessionId = graphService.stream(sink, agentId, input, sessionId, planReview);
		return wire(sink, resolvedSessionId);
	}

	/** 恢复入口(SSE):GET /graph/resume?sessionId=…&approved=true|false&feedback=…(挂起轮的人工确认;新流接上断点续跑) */
	@GetMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphSseChunk>> resume(@RequestParam("sessionId") String sessionId,
			@RequestParam("approved") boolean approved,
			@RequestParam(value = "feedback", required = false) String feedback,
			ServerHttpResponse response) {
		noCache(response);
		Sinks.Many<ServerSentEvent<GraphSseChunk>> sink = Sinks.many().unicast().onBackpressureBuffer();
		graphService.resume(sink, sessionId, approved, feedback);
		return wire(sink, sessionId);
	}

	/** SSE 公共头:不缓存 */
	private static void noCache(ServerHttpResponse response) {
		response.getHeaders().add("Cache-Control", "no-cache");
	}

	/** SSE 管道公共接线:帧过滤(文本帧空文本不推,SQL 帧可重复推送)+ 断连/出错兜底停止 */
	private Flux<ServerSentEvent<GraphSseChunk>> wire(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink,
			String sessionId) {
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
				log.debug("客户端断开,停止执行: sessionId={}", sessionId);
				graphService.stop(sessionId);
			})
			.doOnError(error -> {
				log.error("SSE 管道出错: sessionId={}", sessionId, error);
				graphService.stop(sessionId);
			});
	}

	/** 停止:按会话键(该会话的运行现场与挂起计划一起处理;全内存操作+异步释放,无阻塞) */
	@PostMapping("/stop/{sessionId}")
	public ApiResponse<Void> stop(@PathVariable("sessionId") String sessionId) {
		graphService.stop(sessionId);
		return ApiResponse.success();
	}

	/** 清某会话键下的图侧记忆(客户端编排"删会话"时调用;JDBC 删除调度到弹性线程,不占事件循环) */
	@DeleteMapping("/memory/{sessionId}")
	public Mono<ApiResponse<Void>> clearMemory(@PathVariable("sessionId") String sessionId) {
		return Mono.fromCallable(() -> {
			graphService.clearMemory(sessionId);
			return ApiResponse.<Void>success();
		}).subscribeOn(Schedulers.boundedElastic());
	}

}
