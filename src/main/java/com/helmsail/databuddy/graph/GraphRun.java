package com.helmsail.databuddy.graph;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.codec.ServerSentEvent;

import lombok.Getter;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * 一次执行的现场(运行表的值,键 = 线程键 threadId):输出口 sink、要掐的订阅 disposable、
 * agentId/输入与最终回复、停止旗标——都收在这一个对象里,只在"运行期间"存在。
 * 一线程一会话:threadId 的值即会话键(对外帧与 HTTP 仍用 sessionId 这一业务词)
 */
@Getter
class GraphRun {

	private final String threadId;

	private final long agentId;

	private final String input;

	private final Sinks.Many<ServerSentEvent<GraphSseChunk>> sink;

	private volatile String finalAnswer;

	/** 已播报的过程状态(step 帧去重:同一状态只播一次) */
	private volatile String lastStep;

	/** 已播报的 SQL(sql 帧去重:同一文本只播一次) */
	private volatile String lastSql;

	/** 已播报的结果(SQL_RESULT 帧去重) */
	private volatile String lastResult;

	private volatile Disposable disposable;

	private final AtomicBoolean stopped = new AtomicBoolean(false);

	GraphRun(String threadId, long agentId, String input, Sinks.Many<ServerSentEvent<GraphSseChunk>> sink) {
		this.threadId = threadId;
		this.agentId = agentId;
		this.input = input;
		this.sink = sink;
	}

	void setFinalAnswer(String finalAnswer) {
		this.finalAnswer = finalAnswer;
	}

	void setLastStep(String lastStep) {
		this.lastStep = lastStep;
	}

	void setLastSql(String lastSql) {
		this.lastSql = lastSql;
	}

	void setLastResult(String lastResult) {
		this.lastResult = lastResult;
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
