package com.helmsail.databuddy.middle.graph;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;

import lombok.Getter;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

/**
 * 一条图的执行线(登记表的值,键 = 线程键 threadId):输出口 sink、要掐的订阅 disposable、
 * agentId/输入与最终回复、停止旗标、帧去重游标——都收在这一个对象里,只在"运行期间"存在。
 * 现场自管四路收尾(作废/完成/挂起/失败)与过程帧去重播报,GraphService 只编排"何时"。
 * 一线程一会话:threadId 的值即会话键(对外帧与 HTTP 仍用 sessionId 这一业务词)
 */
class GraphThread {

	@Getter
	private final String threadId;

	@Getter
	private final long agentId;

	@Getter
	private final String input;

	/** 轻档模式(MCP):规划不调 LLM / 跳过报告 / 不收尾回写记忆(同一 run 入口,参数决定图内走法) */
	@Getter
	private final boolean nl2sqlMode;

	private final Sinks.Many<ServerSentEvent<GraphSseChunk>> sink;

	/** 输出口(协议层):帧构造 + 会话键填充 + 投递降噪都收在发射器里 */
	private final GraphSseEmitter frames;

	@Getter
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

	boolean isStopped() {
		return stopped.get();
	}

	// —— 生命周期收尾:四路(作废 / 完成 / 挂起 / 失败),流一律由现场自己收束 ——

	/** 作废现场(被替换 / 图废弃共用):标记停止 + 掐订阅(若已建) + 收束输出流 */
	void terminate() {
		stopped.set(true);
		Disposable current = disposable;
		if (current != null) {
			current.dispose();
		}
		sink.tryEmitComplete();
	}

	/** 正常收尾:最终回复(有则整段播报)→ done 帧 → 收束输出流 */
	void finish() {
		if (StringUtils.hasText(finalAnswer)) {
			frames.text(finalAnswer);
		}
		frames.done();
		sink.tryEmitComplete();
	}

	/** 挂起收尾(计划待确认):plan 帧 → done 帧 → 收束输出流;检查点保留由服务侧编排 */
	void suspend(String planJson) {
		frames.plan(planJson);
		frames.done();
		sink.tryEmitComplete();
	}

	/** 失败收尾:error 帧 → 收束输出流(失败轮不写记忆,记忆保持干净) */
	void abort(String message) {
		frames.error(message);
		sink.tryEmitComplete();
	}

	// —— 节点产出:END 记录最终回复;过程三帧去重播报(同一内容只播一次,空不播) ——

	/** 记录最终回复(END 帧提取;空则忽略) */
	void collectAnswer(String answer) {
		if (StringUtils.hasText(answer)) {
			finalAnswer = answer;
		}
	}

	/** 过程状态帧(step) */
	void step(String node, String note) {
		if (StringUtils.hasText(note) && !note.equals(lastStep)) {
			lastStep = note;
			frames.step(node, note);
		}
	}

	/** SQL 帧 */
	void sql(String node, String text) {
		if (StringUtils.hasText(text) && !text.equals(lastSql)) {
			lastSql = text;
			frames.sql(node, text);
		}
	}

	/** 结果帧 */
	void result(String node, String text) {
		if (StringUtils.hasText(text) && !text.equals(lastResult)) {
			lastResult = text;
			frames.result(node, text);
		}
	}

}
