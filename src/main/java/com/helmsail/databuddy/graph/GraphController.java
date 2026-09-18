package com.helmsail.databuddy.graph;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 图入口(HTTP 边界):建输出口(sink)交给图服务,转发其流;断连/出错时兜底停止图执行
 */
@Slf4j
@RestController
@RequestMapping("/graph")
public class GraphController {

	private final GraphService graphService;

	public GraphController(GraphService graphService) {
		this.graphService = graphService;
	}

	@PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<GraphNodeResponse>> run(@RequestBody GraphRequest request) {
		if (!StringUtils.hasText(request.getQuery())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "query 不能为空");
		}
		Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		graphService.graphStreamProcess(sink, request);

		// threadId 已由服务层就地补写(缺失时生成);订阅由图服务托管,连接断开不会自动停图,故在此兜底停止,防止空转
		return sink.asFlux()
			.doOnCancel(() -> {
				log.info("连接取消(用户停止或断开),停止图执行: threadId={}", request.getThreadId());
				graphService.stopStreamProcessing(request.getThreadId());
			})
			.doOnError(e -> {
				log.warn("流出错,停止图执行: threadId={}", request.getThreadId(), e);
				graphService.stopStreamProcessing(request.getThreadId());
			});
	}

	/** 显式停止:连接未断开也可停(前端"停止"按钮的服务端入口) */
	@PostMapping("/stop")
	public void stop(@RequestParam("threadId") String threadId) {
		graphService.stopStreamProcessing(threadId);
	}

}
