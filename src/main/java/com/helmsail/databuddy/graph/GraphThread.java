package com.helmsail.databuddy.graph;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.codec.ServerSentEvent;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * 一条图的执行线(登记表的值,键 = 线程键 threadId):输出口 sink、要掐的订阅 disposable、
 * agentId/输入与最终回复、停止旗标、帧去重游标——都收在这一个对象里,只在"运行期间"存在。
 * 一线程一会话:threadId 的值即会话键(对外帧与 HTTP 仍用 sessionId 这一业务词)
 */
@Getter
@Setter(AccessLevel.PACKAGE)
class GraphThread {

	private final String threadId;

	private final long agentId;

	private final String input;

	/** 轻档模式(MCP):规划不调 LLM / 跳过报告 / 不收尾回写记忆(同一 run 入口,参数决定图内走法) */
	private final boolean nl2sqlMode;

	private final Sinks.Many<ServerSentEvent<GraphSseChunk>> sink;

	/** 输出口(协议层):帧构造 + 会话键填充 + 投递降噪都收在发射器里 */
	private final GraphSseEmitter frames;

	private volatile String finalAnswer;

	/** 已播报的过程状态(step 帧去重:同一状态只播一次) */
	private volatile String lastStep;

	/** 已播报的 SQL(sql 帧去重:同一文本只播一次) */
	private volatile String lastSql;

	/** 已播报的结果(SQL_RESULT 帧去重) */
	private volatile String lastResult;

	private volatile Disposable disposable;

	private final AtomicBoolean stopped = new AtomicBoolean(false);

	GraphThread(String threadId, long agentId, String input, Sinks.Many<ServerSentEvent<GraphSseChunk>> sink,
			boolean nl2sqlMode) {
		this.threadId = threadId;
		this.agentId = agentId;
		this.input = input;
		this.sink = sink;
		this.nl2sqlMode = nl2sqlMode;
		this.frames = new GraphSseEmitter(threadId, sink);
	}

	/** 订阅建立后回填;若期间已被要求停止,立即掐掉 */
	void setDisposable(Disposable disposable) {
		this.disposable = disposable;
		if (stopped.get()) {
			disposable.dispose();
		}
	}

	void stop() {
		stopped.set(true);
		Disposable current = disposable;
		if (current != null) {
			current.dispose();
		}
	}

	boolean isStopped() {
		return stopped.get();
	}

}
