package com.helmsail.databuddy.graph;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.helmsail.databuddy.agent.AgentService;
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
 * 图的服务:只做执行编排。图由 GraphConfig 装配注入;本服务自订阅图、组装事件
 * (step 过程帧 / plan 计划帧 / sql 帧 / result 结果帧 / text 正文 / done / error)、登记运行。
 * 线程键 = 官方 RunnableConfig.threadId(一线程一会话;值即业务侧会话键 sessionId):检查点挂在会话键下——
 * 跑完/停止/出错/开跑前释放、挂起轮保留(唯一例外,持久于库、跨重启有效)。
 * 挂起态与恢复全部走框架检查点:getState(next 含 PLAN_REVIEW = 挂起中)+ updateState(写决定)+ stream(null) 续跑,
 * 无自有登记/超时/清扫。入口校验失败与执行期错误统一以 error 帧回传(流式客户端的唯一可达通道);
 * 记忆读写都在图外:进图前 buildContext 注入 HISTORY,跑完 finishTurn 写回
 */
@Slf4j
@Service
public class GraphService {

	private final CompiledGraph graph;

	private final SessionMemoryService sessionMemory;

	private final AgentService agentService;

	/** 检查点保存器(框架):释放的唯一通道(标记);挂起查询与续跑走 graph 自身 */
	private final BaseCheckpointSaver checkpointSaver;

	/** 运行表:threadId → 现场;同会话至多一个(新消息先停旧现场);停止/断连兜底靠它 */
	private final Map<String, GraphRun> runningRuns = new ConcurrentHashMap<>();

	public GraphService(CompiledGraph graph, SessionMemoryService sessionMemory, AgentService agentService,
			BaseCheckpointSaver checkpointSaver) {
		this.graph = graph;
		this.sessionMemory = sessionMemory;
		this.agentService = agentService;
		this.checkpointSaver = checkpointSaver;
	}

	/** 发起一次执行(入口):agentId/input 必填;sessionId 缺省则生成(随事件回传);planReview = 人工确认闸开关(默认关);返回会话键 */
	public String stream(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink, long agentId, String input, String sessionId,
			boolean planReview) {
		String resolved = StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();
		if (!StringUtils.hasText(input)) { // 入口校验失败走流内 error 帧(不再抛 HTTP 错误:EventSource 读不到信封)
			fail(sink, resolved, "input 不能为空");
			return resolved;
		}
		GraphRun run = new GraphRun(resolved, agentId, input, sink);
		// 一线程一会话:put 原子替换旧现场(防御双发;旧检查点由其后的开局释放清理)
		GraphRun previous = runningRuns.put(run.getThreadId(), run);
		if (previous != null) {
			log.info("同线程旧现场先停: threadId={}", resolved);
			previous.stop();
			previous.getSink().tryEmitComplete();
		}
		// 进图前的记忆读取、检查点释放与建流订阅都是阻塞活:整体挪到弹性线程,不占事件循环
		Mono.fromRunnable(() -> start(run, planReview))
			.subscribeOn(Schedulers.boundedElastic())
			.subscribe(ignored -> { }, error -> onError(run, error));
		return run.getThreadId();
	}

	/** 恢复入口(人工确认):校验/防重/写决定/续跑整体调度到弹性线程(检查点读写都是阻塞活,不占事件循环);失败走 error 帧 | 返回会话键 */
	public String resume(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink, String sessionId, boolean approved,
			String feedback) {
		Mono.fromRunnable(() -> startResume(sink, sessionId, approved, feedback))
			.subscribeOn(Schedulers.boundedElastic())
			.subscribe(ignored -> { }, error -> {
				log.error("恢复流程异常: threadId={}", sessionId, error);
				fail(sink, sessionId, "恢复失败");
			});
		return sessionId;
	}

	private void start(GraphRun run, boolean planReview) {
		if (run.isStopped()) {
			return;
		}
		agentService.get(run.getAgentId()); // 入口校验:agent 不存在 → 错误帧,不进图
		release(run.getThreadId()); // 开跑前释放旧检查点(框架语义要求:防上轮未确认的挂起状态与新轮混写)
		String history = sessionMemory.buildContext(run.getThreadId());
		Map<String, Object> init = Map.of(GraphKeys.INPUT, run.getInput(), GraphKeys.AGENT_ID, run.getAgentId(),
				GraphKeys.HISTORY, history, GraphKeys.PLAN_REVIEW_ENABLED, planReview);
		Flux<NodeOutput> outputs = graph.stream(init, RunnableConfig.builder().threadId(run.getThreadId()).build());
		Disposable disposable = outputs.subscribeOn(Schedulers.boundedElastic())
			.subscribe(output -> onOutput(run, output), error -> onError(run, error), () -> onComplete(run));
		run.setDisposable(disposable);
	}

	/**
	 * 恢复的完整流程(弹性线程):查检查点校验挂起(next 含 PLAN_REVIEW)→ putIfAbsent 防重复确认 →
	 * 写决定(updateState)→ 同线程键从断点续跑(不得重置线程——要保留的正是该键下的检查点)。
	 * 校验类失败以 error 帧回传,不抛异常
	 */
	private void startResume(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink, String sessionId, boolean approved,
			String feedback) {
		// 快速提示:该线程现场还在跑(精确防重由下方 putIfAbsent 兜底)
		if (runningRuns.containsKey(sessionId)) {
			fail(sink, sessionId, "该会话正在执行,请稍后再确认");
			return;
		}
		StateSnapshot snapshot;
		try {
			snapshot = graph.getState(RunnableConfig.builder().threadId(sessionId).build());
		}
		catch (Exception e) {
			log.debug("取图状态失败(无检查点): threadId={}, {}", sessionId, e.getMessage());
			snapshot = null;
		}
		if (snapshot == null || !pendingReview(snapshot)) {
			fail(sink, sessionId, "计划不存在或已失效");
			return;
		}
		Object agentIdValue = snapshot.state().value(GraphKeys.AGENT_ID).orElse(null);
		if (!(agentIdValue instanceof Number number)) {
			fail(sink, sessionId, "计划不存在或已失效");
			return;
		}
		GraphRun run = new GraphRun(sessionId, number.longValue(), "(计划确认)", sink);
		if (runningRuns.putIfAbsent(sessionId, run) != null) { // 防重复确认(原子):并发重复请求只放行一个
			fail(sink, sessionId, "该会话正在执行,请稍后再确认");
			return;
		}
		if (run.isStopped()) {
			return;
		}
		RunnableConfig config;
		try {
			config = graph.updateState(RunnableConfig.builder().threadId(run.getThreadId()).build(),
					Map.of(GraphKeys.PLAN_REVIEW_DECISION,
							Map.of("approved", approved, "feedback", feedback == null ? "" : feedback)));
		}
		catch (Exception e) {
			onError(run, e);
			return;
		}
		log.info("从断点恢复执行: threadId={}, approved={}", run.getThreadId(), approved);
		Flux<NodeOutput> outputs = graph.stream(null, config);
		Disposable disposable = outputs.subscribeOn(Schedulers.boundedElastic())
			.subscribe(output -> onOutput(run, output), error -> onError(run, error), () -> onComplete(run));
		run.setDisposable(disposable);
	}

	/**
	 * 轻档入口(MCP):同步跑图(无帧无流),返回最终状态;NL2SQL_MODE 开 → 规划不调 LLM、走完跳过报告。
	 * 临时 threadId 跑完即释放(不挂进检查点表);阻塞活调度到弹性线程后取结果;
	 * 结果解释(取 SQL_QUERY / SQL_RESULT / 终止语)在调用方(McpServerService)
	 */
	public OverAllState runLight(long agentId, String question) {
		if (!StringUtils.hasText(question)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "question 不能为空");
		}
		agentService.get(agentId); // 入口校验:agent 不存在 → 业务异常(工具层转错误文本)
		String threadId = UUID.randomUUID().toString();
		Map<String, Object> init = Map.of(GraphKeys.INPUT, question, GraphKeys.AGENT_ID, agentId, GraphKeys.HISTORY, "",
				GraphKeys.NL2SQL_MODE, true);
		try {
			return Mono
				.fromCallable(() -> graph.invoke(init, RunnableConfig.builder().threadId(threadId).build())
					.orElseThrow(() -> new IllegalStateException("轻档执行未产出状态")))
				.subscribeOn(Schedulers.boundedElastic())
				.toFuture()
				.get();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "轻档执行被中断", e);
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "轻档执行失败: " + cause.getMessage(), cause);
		}
		finally {
			release(threadId);
		}
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
			return;
		}
		// 中间节点完成:写有过程状态(NODE_STATUS)的节点推一条 step 帧(轻量过程播报;同一状态只播一次)
		String note = output.state().value(GraphKeys.NODE_STATUS, String.class).orElse(null);
		if (StringUtils.hasText(note) && !note.equals(run.getLastStep())) {
			run.setLastStep(note);
			emit(run, GraphSseChunk.builder().eventType(GraphKeys.STEP).node(output.node()).text(note).build());
		}
		// SQL 帧:新生成/重写的 SQL(去重后下发,text = SQL 文本)
		String sql = output.state().value(GraphKeys.SQL_QUERY, String.class).orElse(null);
		if (StringUtils.hasText(sql) && !sql.equals(run.getLastSql())) {
			run.setLastSql(sql);
			emit(run, GraphSseChunk.builder().eventType(GraphKeys.SQL).node(output.node()).text(sql).build());
		}
		// 结果帧:最近一次 SQL 执行结果(去重后下发,text = 契约 JSON)
		String result = output.state().value(GraphKeys.SQL_RESULT, String.class).orElse(null);
		if (StringUtils.hasText(result) && !result.equals(run.getLastResult())) {
			run.setLastResult(result);
			emit(run, GraphSseChunk.builder().eventType(GraphKeys.RESULT).node(output.node()).text(result).build());
		}
	}

	private void onComplete(GraphRun run) {
		// 条件删:仅"自己仍是现任现场"才收尾——防旧轮残响(dispose 竞态窗口)误删继任现场/误放继任检查点/污染记忆
		if (!runningRuns.remove(run.getThreadId(), run)) {
			log.debug("现场已被替换,旧轮收尾跳过: threadId={}", run.getThreadId());
			return;
		}
		if (suspend(run)) {
			return;
		}
		release(run.getThreadId());
		String answer = run.getFinalAnswer();
		if (StringUtils.hasText(answer)) {
			emit(run, GraphSseChunk.builder().eventType(GraphKeys.TEXT).text(answer).build()); // 最终回复整段播报
		}
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.DONE).build());
		run.getSink().tryEmitComplete();
		// done 已送出:慢活(必要时的 AI 压缩)在这一步,不挡用户
		sessionMemory.finishTurn(run.getThreadId(), run.getInput(), answer);
	}

	/**
	 * 挂起判定与处理:停在中断点(StateSnapshot.next 含待执行的真实节点)→ 保留检查点 + 下发 plan 帧;
	 * 自然跑完(下一跳恰为终点标记 __END__,已实测)返回 false。取状态异常按自然完成处理(防御)
	 */
	private boolean suspend(GraphRun run) {
		StateSnapshot snapshot;
		try {
			snapshot = graph.getState(RunnableConfig.builder().threadId(run.getThreadId()).build());
		}
		catch (Exception e) {
			log.debug("取图状态失败,按自然完成处理: threadId={}, {}", run.getThreadId(), e.getMessage());
			return false;
		}
		if (snapshot == null || !StringUtils.hasText(snapshot.next())) {
			return false;
		}
		// 真挂起 = 下一跳里存在待执行的真实节点;自然跑完的 next 恰为终点标记(如 __END__),不算挂起
		boolean hasPendingNode = Arrays.stream(snapshot.next().split(","))
			.map(String::trim)
			.anyMatch(node -> StringUtils.hasText(node) && !StateGraph.END.equals(node));
		if (!hasPendingNode) {
			return false;
		}
		String planJson = snapshot.state().value(GraphKeys.PLAN_JSON, String.class).orElse("");
		log.info("执行挂起(计划待确认): threadId={}, next={}", run.getThreadId(), snapshot.next());
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.PLAN).text(planJson).build());
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.DONE).build());
		run.getSink().tryEmitComplete();
		// 记忆:用户看到了什么就记什么(计划摘要入上文,确认后下一轮不困惑)
		sessionMemory.finishTurn(run.getThreadId(), run.getInput(), "【待确认的执行计划】\n" + planJson);
		return true;
	}

	private void onError(GraphRun run, Throwable error) {
		// 条件删:仅"现任"才释放检查点(被替换时由继任轮的开局释放负责),防误放继任现场正在用的检查点
		if (runningRuns.remove(run.getThreadId(), run)) {
			release(run.getThreadId());
		}
		log.error("执行失败: threadId={}", run.getThreadId(), error);
		String message = StringUtils.hasText(error.getMessage()) ? error.getMessage() : "执行失败";
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.ERROR).text(message).build());
		run.getSink().tryEmitComplete();
		// 失败轮不写记忆,记忆保持干净
	}

	/** 停止:按线程键(值 = 会话键;运行中 = 现场作废;挂起中 = 取消计划,检查点释放;流干净收束) */
	public void stop(String threadId) {
		GraphRun run = runningRuns.remove(threadId);
		if (run != null) {
			log.info("停止执行: threadId={}", threadId);
			run.stop();
			run.getSink().tryEmitComplete();
		}
		releaseAsync(threadId); // 挂起轮的取消一并在此:释放检查点(幂等,无检查点时零副作用)
	}

	/** 清某会话下的记忆(客户端"删会话"编排调用;只清自己的记忆,本服务不解释键含义) */
	public void clearMemory(String sessionId) {
		sessionMemory.deleteConversation(sessionId);
	}

	/** 入口失败:统一以 error 帧+收束回传(EventSource 读不到 HTTP 信封,流内 error 是唯一可达通道) */
	private static void fail(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink, String threadId, String message) {
		GraphSseChunk chunk = GraphSseChunk.builder().eventType(GraphKeys.ERROR).text(message).build();
		chunk.setSessionId(threadId);
		sink.tryEmitNext(ServerSentEvent.builder(chunk).event(GraphKeys.ERROR).build());
		sink.tryEmitComplete();
	}

	/** 挂起判定:下一跳含 PLAN_REVIEW 节点(编译期中断在该节点前;决定写入不改 next,由续跑消费) */
	private static boolean pendingReview(StateSnapshot snapshot) {
		return StringUtils.hasText(snapshot.next()) && Arrays.stream(snapshot.next().split(","))
			.map(String::trim)
			.anyMatch(GraphKeys.PLAN_REVIEW::equals);
	}

	/** 释放检查点(框架标记:引擎不再读该线程,行保留;幂等,不存在/已释放时降噪) */
	private void release(String threadId) {
		try {
			checkpointSaver.release(RunnableConfig.builder().threadId(threadId).build());
		}
		catch (Exception e) {
			log.debug("检查点释放跳过(不存在/已释放): threadId={}, {}", threadId, e.getMessage());
		}
	}

	/** 释放检查点(异步版):stop 路径可能来自 Netty 事件循环,把阻塞的 MySQL 往返挪到弹性线程 */
	private void releaseAsync(String threadId) {
		Mono.fromRunnable(() -> release(threadId)).subscribeOn(Schedulers.boundedElastic()).subscribe();
	}

	private void emit(GraphRun run, GraphSseChunk chunk) {
		chunk.setSessionId(run.getThreadId()); // 帧字段用对外业务词:会话键(= 线程键)
		Sinks.EmitResult result = run.getSink()
			.tryEmitNext(ServerSentEvent.builder(chunk).event(chunk.getEventType()).build());
		if (result.isFailure()) {
			log.debug("片段未送入(流已收束或客户端断开): threadId={}, type={}, result={}", run.getThreadId(),
					chunk.getEventType(), result);
		}
	}

}
