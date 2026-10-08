package com.helmsail.databuddy.graph;

import org.springframework.http.codec.ServerSentEvent;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Sinks;

/**
 * 帧发射器:一条执行线的输出口(协议层)——业务文本 → GraphSseChunk 帧 → ServerSentEvent 装包,
 * 统一填充会话键、投递 sink、失败降噪(流已收束 / 客户端断开)。
 * GraphService 只管"何时发什么",不再碰帧的形状
 */
@Slf4j
class GraphSseEmitter {

	private final String sessionId;

	private final Sinks.Many<ServerSentEvent<GraphSseChunk>> sink;

	GraphSseEmitter(String sessionId, Sinks.Many<ServerSentEvent<GraphSseChunk>> sink) {
		this.sessionId = sessionId;
		this.sink = sink;
	}

	/** 过程状态帧(step;节点侧 NODE_STATUS 原文) */
	void step(String node, String text) {
		emit(GraphSseChunk.builder().eventType(GraphKeys.STEP).node(node).text(text).build());
	}

	/** SQL 帧(新生成 / 重写的 SQL 原文) */
	void sql(String node, String text) {
		emit(GraphSseChunk.builder().eventType(GraphKeys.SQL).node(node).text(text).build());
	}

	/** 结果帧(契约 JSON) */
	void result(String node, String text) {
		emit(GraphSseChunk.builder().eventType(GraphKeys.RESULT).node(node).text(text).build());
	}

	/** 计划帧(挂起轮:待确认计划 JSON) */
	void plan(String text) {
		emit(GraphSseChunk.builder().eventType(GraphKeys.PLAN).text(text).build());
	}

	/** 最终回复整段播报 */
	void text(String text) {
		emit(GraphSseChunk.builder().eventType(GraphKeys.TEXT).text(text).build());
	}

	/** 收尾帧 */
	void done() {
		emit(GraphSseChunk.builder().eventType(GraphKeys.DONE).build());
	}

	/** 错误帧(流内唯一可达通道) */
	void error(String message) {
		push(GraphSseChunk.errorFrame(sessionId, message));
	}

	/** 构造 + 填充会话键 + 装包 + 投递 */
	private void emit(GraphSseChunk chunk) {
		chunk.setSessionId(sessionId); // 帧字段用对外业务词:会话键(= 线程键)
		push(ServerSentEvent.builder(chunk).event(chunk.getEventType()).build());
	}

	private void push(ServerSentEvent<GraphSseChunk> sse) {
		Sinks.EmitResult result = sink.tryEmitNext(sse);
		if (result.isFailure()) {
			log.debug("片段未送入(流已收束或客户端断开): sessionId={}, type={}, result={}", sessionId,
					sse.data().getEventType(), result);
		}
	}

}
