package com.helmsail.databuddy.graph;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.result.ApiResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 图入口(唯一 Controller):只做 HTTP 层——参数绑定、响应头、转发。
 * SSE 流的组装(建 sink、帧过滤、断连兜底停止)与执行编排全在 GraphService;
 * 图线程键 = 会话键(一线程一会话;对接官方 threadId):发起/恢复/停止/清记忆全部以 sessionId 寻址。
 * 通道边界:run/resume 的入口校验失败与执行期错误统一走流内 error 帧(EventSource 读不到 HTTP 信封);
 * 参数绑定等框架级错误走全局处理器(信封体)
 */
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
		response.getHeaders().add("Cache-Control", "no-cache");
		return graphService.stream(agentId, input, sessionId, planReview);
	}

	/** 恢复入口(SSE):GET /graph/resume?sessionId=…&approved=true|false&feedback=…(挂起轮的人工确认;新流接上断点续跑) */
	@GetMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphSseChunk>> resume(@RequestParam("sessionId") String sessionId,
			@RequestParam("approved") boolean approved,
			@RequestParam(value = "feedback", required = false) String feedback,
			ServerHttpResponse response) {
		response.getHeaders().add("Cache-Control", "no-cache");
		return graphService.resume(sessionId, approved, feedback);
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
