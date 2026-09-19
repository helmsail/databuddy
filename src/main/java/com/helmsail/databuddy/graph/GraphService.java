package com.helmsail.databuddy.graph;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.memory.SessionMemoryService;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 图的服务:只做执行编排(骨架版,骨架期图无节点)。
 * 图由 GraphConfig 装配注入;本服务自订阅图、组装事件、登记运行(停止/断连兜底靠运行表)、
 * 完成/停止/出错时释放检查点,并把"成功的轮"落进会话记忆(在 done 之后执行,不挡用户)。
 * 记忆的读写都在图外:进图前 buildContext 注入 HISTORY,跑完 finishTurn 写回(无输出自动跳过)
 */
@Slf4j
@Service
public class GraphService {

	private final CompiledGraph graph;

	private final BaseCheckpointSaver checkpointSaver;

	private final SessionMemoryService sessionMemory;

	/** 运行表:runId → 现场;停止请求与断连兜底靠它找到"正在跑的订阅"(跑完/停掉即移除) */
	private final Map<String, GraphRun> runningRuns = new ConcurrentHashMap<>();

	public GraphService(CompiledGraph graph, BaseCheckpointSaver checkpointSaver, SessionMemoryService sessionMemory) {
		this.graph = graph;
		this.checkpointSaver = checkpointSaver;
		this.sessionMemory = sessionMemory;
	}

	/** 发起一次执行(入口):input 必填;会话号缺省则生成(随事件回传);返回运行号(停止/日志用) */
	public String stream(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink, String input, String sessionId) {
		if (!StringUtils.hasText(input)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "input 不能为空");
		}
		String resolvedSessionId = StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();
		GraphRun run = new GraphRun(UUID.randomUUID().toString(), resolvedSessionId, input, sink);
		runningRuns.put(run.getRunId(), run);
		// 进图前的记忆读取与建流订阅都是阻塞活:整体挪到弹性线程,不占事件循环
		Mono.fromRunnable(() -> start(run))
			.subscribeOn(Schedulers.boundedElastic())
			.subscribe(ignored -> { }, error -> onError(run, error));
		return run.getRunId();
	}

	private void start(GraphRun run) {
		if (run.isStopped()) {
			return;
		}
		String history = sessionMemory.buildContext(run.getSessionId());
		Map<String, Object> init = Map.of(GraphKeys.INPUT, run.getInput(), GraphKeys.HISTORY, history);
		Flux<NodeOutput> outputs = graph.stream(init, RunnableConfig.builder().threadId(run.getRunId()).build());
		Disposable disposable = outputs.subscribeOn(Schedulers.boundedElastic())
			.subscribe(output -> onOutput(run, output), error -> onError(run, error), () -> onComplete(run));
		run.setDisposable(disposable);
	}

	private void onOutput(GraphRun run, NodeOutput output) {
		if (output.isSTART()) {
			return; // START 帧无内容,不对外
		}
		if (output.isEND()) {
			String answer = output.state().value(GraphKeys.FINAL_ANSWER, String.class).orElse(null);
			if (StringUtils.hasText(answer)) {
				run.setFinalAnswer(answer);
			}
		}
		// 非流式节点不出片段帧;将来流式节点接入后,在这里把片段转成事件推给前端
	}

	private void onComplete(GraphRun run) {
		runningRuns.remove(run.getRunId());
		releaseCheckpoint(run);
		String answer = run.getFinalAnswer();
		if (StringUtils.hasText(answer)) {
			emit(run, GraphSseChunk.builder().eventType(GraphKeys.TEXT).text(answer).build()); // 最终回复整段播报
		}
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.DONE).build());
		run.getSink().tryEmitComplete();
		// done 已送出:慢活(必要时的 AI 压缩)在这一步,不挡用户
		sessionMemory.finishTurn(run.getSessionId(), run.getInput(), answer);
	}

	private void onError(GraphRun run, Throwable error) {
		runningRuns.remove(run.getRunId());
		releaseCheckpoint(run);
		log.error("执行失败: runId={}, sessionId={}", run.getRunId(), run.getSessionId(), error);
		String message = StringUtils.hasText(error.getMessage()) ? error.getMessage() : "执行失败";
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.ERROR).text(message).build());
		run.getSink().tryEmitComplete();
		// 失败轮不写记忆,记忆保持干净
	}

	/** 停止:按运行号(现场作废:本轮不写记忆;检查点释放;流干净收束) */
	public void stop(String runId) {
		GraphRun run = runningRuns.remove(runId);
		if (run == null) {
			return;
		}
		log.info("停止执行: runId={}", runId);
		run.stop();
		releaseCheckpoint(run);
		run.getSink().tryEmitComplete();
	}

	/** 停止:按会话号(该会话名下所有运行) */
	public void stopBySession(String sessionId) {
		for (GraphRun run : List.copyOf(runningRuns.values())) {
			if (run.getSessionId().equals(sessionId)) {
				stop(run.getRunId());
			}
		}
	}

	/** 释放检查点:正常完成/停止/出错都作废;将来"挂起等人工"是唯一保留的例外 */
	private void releaseCheckpoint(GraphRun run) {
		try {
			checkpointSaver.release(RunnableConfig.builder().threadId(run.getRunId()).build());
		}
		catch (Exception e) {
			log.warn("检查点释放失败: runId={}", run.getRunId(), e);
		}
	}

	private void emit(GraphRun run, GraphSseChunk chunk) {
		chunk.setRunId(run.getRunId());
		chunk.setSessionId(run.getSessionId());
		Sinks.EmitResult result = run.getSink()
			.tryEmitNext(ServerSentEvent.builder(chunk).event(chunk.getEventType()).build());
		if (result.isFailure()) {
			log.debug("片段未送入(流已收束或客户端断开): runId={}, type={}, result={}", run.getRunId(),
					chunk.getEventType(), result);
		}
	}

}
