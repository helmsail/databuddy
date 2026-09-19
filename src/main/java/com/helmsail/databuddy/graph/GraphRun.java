package com.helmsail.databuddy.graph;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.codec.ServerSentEvent;

import lombok.Getter;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * 一次执行的现场(运行表的值):输出口 sink、要掐的订阅 disposable、
 * 记忆要用的输入与最终回复、停止旗标——都收在这一个对象里,只在"运行期间"存在
 */
@Getter
class GraphRun {

	private final String runId;

	private final String sessionId;

	private final String input;

	private final Sinks.Many<ServerSentEvent<GraphSseChunk>> sink;

	private volatile String finalAnswer;

	private volatile Disposable disposable;

	private final AtomicBoolean stopped = new AtomicBoolean(false);

	GraphRun(String runId, String sessionId, String input, Sinks.Many<ServerSentEvent<GraphSseChunk>> sink) {
		this.runId = runId;
		this.sessionId = sessionId;
		this.input = input;
		this.sink = sink;
	}

	void setFinalAnswer(String finalAnswer) {
		this.finalAnswer = finalAnswer;
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
