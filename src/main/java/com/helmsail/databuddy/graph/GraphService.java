package com.helmsail.databuddy.graph;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
 * 常规轮:跑完/停止/出错都释放检查点,成功的轮落进会话记忆(在 done 之后,不挡用户);
 * 挂起轮(人工确认闸停在中断点):检查点保留("跑完即释放"规则的唯一破例)+ 登记待确认清单 +
 * 下发 plan 帧;恢复:写决定(updateState)+ 同 threadId 从断点续跑(新流);等待窗口超时由定时清扫作废,进程重启由启动钩子清空检查点表(旧挂起一律作废)。
 * 记忆读写都在图外:进图前 buildContext 注入 HISTORY,跑完 finishTurn 写回
 */
@Slf4j
@Service
public class GraphService {

	private final CompiledGraph graph;

	private final BaseCheckpointSaver checkpointSaver;

	private final SessionMemoryService sessionMemory;

	private final AgentService agentService;

	/** 引擎检查点表(GRAPH_THREAD/GRAPH_CHECKPOINT)的清除口:启动清孤儿行用 */
	private final JdbcTemplate jdbcTemplate;

	/** 运行表:runId → 现场;停止请求与断连兜底靠它找到"正在跑的订阅"(跑完/停掉即移除) */
	private final Map<String, GraphRun> runningRuns = new ConcurrentHashMap<>();

	/** 待确认清单:挂起轮的检查点保留期间的登记(runId 即 threadId;确认/作废/超时三出口) */
	private final Map<String, PendingPlan> pendingPlans = new ConcurrentHashMap<>();

	/** 等待窗口:挂起超过该时长即作废(释放检查点,用户再确认时按"已过期"处理) */
	private final Duration planReviewTimeout;

	public GraphService(CompiledGraph graph, BaseCheckpointSaver checkpointSaver, SessionMemoryService sessionMemory,
			AgentService agentService, JdbcTemplate jdbcTemplate,
			@Value("${databuddy.graph.plan-review-timeout:24h}") Duration planReviewTimeout) {
		this.graph = graph;
		this.checkpointSaver = checkpointSaver;
		this.sessionMemory = sessionMemory;
		this.agentService = agentService;
		this.jdbcTemplate = jdbcTemplate;
		this.planReviewTimeout = planReviewTimeout;
	}

	/** 发起一次执行(入口):agentId/input 必填;会话号缺省则生成(随事件回传);planReview = 人工确认闸开关(默认关) */
	public String stream(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink, long agentId, String input, String sessionId,
			boolean planReview) {
		if (!StringUtils.hasText(input)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "input 不能为空");
		}
		String resolvedSessionId = StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();
		// 同会话旧挂起作废:新消息 = 用户放弃等待(规则:同会话至多一个活跃挂起)
		discardPendingsOf(resolvedSessionId);
		GraphRun run = new GraphRun(UUID.randomUUID().toString(), resolvedSessionId, agentId, input, sink);
		runningRuns.put(run.getRunId(), run);
		// 进图前的记忆读取与建流订阅都是阻塞活:整体挪到弹性线程,不占事件循环
		Mono.fromRunnable(() -> start(run, planReview))
			.subscribeOn(Schedulers.boundedElastic())
			.subscribe(ignored -> { }, error -> onError(run, error));
		return run.getRunId();
	}

	/** 恢复入口(人工确认):校验登记 → 写决定 → 同 threadId 从断点续跑 | new sink 继续推帧 | 返回 runId */
	public String resume(Sinks.Many<ServerSentEvent<GraphSseChunk>> sink, String runId, boolean approved, String feedback) {
		PendingPlan pending = pendingPlans.get(runId);
		if (pending == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "计划不存在或已过期(runId=" + runId + ")");
		}
		if (isExpired(pending)) {
			pendingPlans.remove(runId);
			releaseCheckpointAsync(runId);
			throw new BusinessException(ErrorCode.INVALID_INPUT, "计划已过期,请重新提问(runId=" + runId + ")");
		}
		GraphRun run = new GraphRun(runId, pending.sessionId(), pending.agentId(), "(计划确认)", sink);
		runningRuns.put(runId, run);
		Mono.fromRunnable(() -> startResume(run, approved, feedback))
			.subscribeOn(Schedulers.boundedElastic())
			.subscribe(ignored -> { }, error -> onError(run, error));
		return runId;
	}

	private void start(GraphRun run, boolean planReview) {
		if (run.isStopped()) {
			return;
		}
		agentService.get(run.getAgentId()); // 入口校验:agent 不存在 → 错误帧,不进图
		String history = sessionMemory.buildContext(run.getSessionId());
		Map<String, Object> init = Map.of(GraphKeys.INPUT, run.getInput(), GraphKeys.AGENT_ID, run.getAgentId(),
				GraphKeys.HISTORY, history, GraphKeys.PLAN_REVIEW_ENABLED, planReview);
		Flux<NodeOutput> outputs = graph.stream(init, RunnableConfig.builder().threadId(run.getRunId()).build());
		Disposable disposable = outputs.subscribeOn(Schedulers.boundedElastic())
			.subscribe(output -> onOutput(run, output), error -> onError(run, error), () -> onComplete(run));
		run.setDisposable(disposable);
	}

	/** 断点续跑:决定写入状态后以 null 输入继续(参考实现的同款恢复路径) */
	private void startResume(GraphRun run, boolean approved, String feedback) {
		if (run.isStopped()) {
			return;
		}
		RunnableConfig config;
		try {
			config = graph.updateState(RunnableConfig.builder().threadId(run.getRunId()).build(),
					Map.of(GraphKeys.PLAN_REVIEW_DECISION,
							Map.of("approved", approved, "feedback", feedback == null ? "" : feedback)));
		}
		catch (Exception e) {
			onError(run, e);
			return;
		}
		log.info("从断点恢复执行: runId={}, approved={}", run.getRunId(), approved);
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
			releaseCheckpoint(threadId);
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
		runningRuns.remove(run.getRunId());
		pendingPlans.remove(run.getRunId()); // 恢复轮跑完:登记随之清除(平时无登记,幂等)
		if (suspend(run)) {
			return;
		}
		releaseCheckpoint(run.getRunId());
		String answer = run.getFinalAnswer();
		if (StringUtils.hasText(answer)) {
			emit(run, GraphSseChunk.builder().eventType(GraphKeys.TEXT).text(answer).build()); // 最终回复整段播报
		}
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.DONE).build());
		run.getSink().tryEmitComplete();
		// done 已送出:慢活(必要时的 AI 压缩)在这一步,不挡用户
		sessionMemory.finishTurn(run.getSessionId(), run.getInput(), answer);
	}

	/**
	 * 挂起判定与处理:停在中断点(StateSnapshot.next 含待执行的真实节点)→ 保留检查点 +
	 * 登记待确认 + 下发 plan 帧;自然跑完(下一跳恰为终点标记 __END__,已实测)返回 false。
	 * 取状态异常按自然完成处理(防御)
	 */
	private boolean suspend(GraphRun run) {
		StateSnapshot snapshot;
		try {
			snapshot = graph.getState(RunnableConfig.builder().threadId(run.getRunId()).build());
		}
		catch (Exception e) {
			log.debug("取图状态失败,按自然完成处理: runId={}, {}", run.getRunId(), e.getMessage());
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
		log.info("执行挂起(计划待确认): runId={}, next={}", run.getRunId(), snapshot.next());
		pendingPlans.put(run.getRunId(),
				new PendingPlan(run.getRunId(), run.getSessionId(), run.getAgentId(), System.nanoTime()));
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.PLAN).text(planJson).build());
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.DONE).build());
		run.getSink().tryEmitComplete();
		// 记忆:用户看到了什么就记什么(计划摘要入上文,确认后下一轮不困惑)
		sessionMemory.finishTurn(run.getSessionId(), run.getInput(), "【待确认的执行计划】\n" + planJson);
		return true;
	}

	private void onError(GraphRun run, Throwable error) {
		runningRuns.remove(run.getRunId());
		pendingPlans.remove(run.getRunId());
		releaseCheckpoint(run.getRunId());
		log.error("执行失败: runId={}, sessionId={}", run.getRunId(), run.getSessionId(), error);
		String message = StringUtils.hasText(error.getMessage()) ? error.getMessage() : "执行失败";
		emit(run, GraphSseChunk.builder().eventType(GraphKeys.ERROR).text(message).build());
		run.getSink().tryEmitComplete();
		// 失败轮不写记忆,记忆保持干净
	}

	/** 停止:按运行号(运行中 = 现场作废;挂起中 = 取消计划,检查点释放;流干净收束) */
	public void stop(String runId) {
		GraphRun run = runningRuns.remove(runId);
		if (run != null) {
			log.info("停止执行: runId={}", runId);
			run.stop();
			releaseCheckpointAsync(runId);
			run.getSink().tryEmitComplete();
			return;
		}
		if (pendingPlans.remove(runId) != null) {
			log.info("取消挂起计划: runId={}", runId);
			releaseCheckpointAsync(runId);
		}
	}

	/** 停止:按会话号(该会话名下所有运行与挂起) */
	public void stopBySession(String sessionId) {
		for (GraphRun run : List.copyOf(runningRuns.values())) {
			if (run.getSessionId().equals(sessionId)) {
				stop(run.getRunId());
			}
		}
		for (PendingPlan pending : List.copyOf(pendingPlans.values())) {
			if (pending.sessionId().equals(sessionId)) {
				stop(pending.runId());
			}
		}
	}

	/** 清某线程键下的图侧记忆(客户端"删会话"编排调用;只清自己的记忆,图不解释键含义) */
	public void clearMemory(String sessionId) {
		sessionMemory.deleteBySession(sessionId);
	}

	/** 定时清扫:超过等待窗口的挂起作废(释放检查点;用户再确认时按"已过期"处理) */
	@Scheduled(fixedDelayString = "${databuddy.graph.plan-review-sweep:10m}",
			initialDelayString = "${databuddy.graph.plan-review-sweep:10m}")
	public void sweepExpiredPendings() {
		for (PendingPlan pending : List.copyOf(pendingPlans.values())) {
			if (isExpired(pending) && pendingPlans.remove(pending.runId()) != null) {
				log.info("挂起超时作废: runId={}", pending.runId());
				releaseCheckpoint(pending.runId());
			}
		}
	}

	/**
	 * 启动清理:进程重启后内存待确认清单归零,MySQL 里的挂起检查点行成为孤儿(引擎表,单机独占,清空无副作用);
	 * 重启后旧挂起一律按"已过期"处理(与惰性/定时过期同一出口)。失败只记日志,不拦启动
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void cleanupOrphanCheckpoints() {
		try {
			jdbcTemplate.update("DELETE FROM GRAPH_CHECKPOINT");
			jdbcTemplate.update("DELETE FROM GRAPH_THREAD");
			log.info("启动清理:图检查点表已清空(重启后旧挂起作废)");
		}
		catch (Exception e) {
			log.warn("启动清理检查点失败(忽略): {}", e.getMessage());
		}
	}

	/** 同会话旧挂起作废(新消息 = 放弃等待;释放检查点,登记移除) */
	private void discardPendingsOf(String sessionId) {
		for (PendingPlan pending : List.copyOf(pendingPlans.values())) {
			if (pending.sessionId().equals(sessionId) && pendingPlans.remove(pending.runId()) != null) {
				log.info("同会话旧挂起作废: runId={}", pending.runId());
				releaseCheckpointAsync(pending.runId());
			}
		}
	}

	private boolean isExpired(PendingPlan pending) {
		return System.nanoTime() - pending.createdAtNanos() > planReviewTimeout.toNanos();
	}

	/**
	 * 释放检查点(正常完成/停止/出错都作废;挂起轮是唯一保留的例外,确认/作废/超时后照常释放):
	 * 引擎级释放(标记,清引擎内存缓存)+ 物理清行(防 released 行累积——行会随"跑完即释放"无线堆积,已实测)。
	 * 幂等:线程不存在/已释放时引擎抛"not found or already released",属正常场景(如错误轮/重复释放),降噪处理
	 */
	private void releaseCheckpoint(String threadId) {
		try {
			checkpointSaver.release(RunnableConfig.builder().threadId(threadId).build());
		}
		catch (Exception e) {
			log.debug("引擎级检查点释放跳过(不存在/已释放): threadId={}, {}", threadId, e.getMessage());
		}
		try {
			jdbcTemplate.update(
					"DELETE FROM GRAPH_CHECKPOINT WHERE thread_id IN (SELECT thread_id FROM GRAPH_THREAD WHERE thread_name = ?)",
					threadId);
			jdbcTemplate.update("DELETE FROM GRAPH_THREAD WHERE thread_name = ?", threadId);
		}
		catch (Exception e) {
			log.warn("检查点行清理失败: threadId={}", threadId, e);
		}
	}

	/** 释放检查点(异步版):stop/作废路径可能来自 Netty 事件循环,把阻塞的 MySQL 往返整体挪到弹性线程 */
	private void releaseCheckpointAsync(String threadId) {
		Mono.fromRunnable(() -> releaseCheckpoint(threadId)).subscribeOn(Schedulers.boundedElastic()).subscribe();
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

	/** 待确认计划登记(内存态:进程重启即作废,与单机定位一致) */
	private record PendingPlan(String runId, String sessionId, long agentId, long createdAtNanos) {
	}

}
