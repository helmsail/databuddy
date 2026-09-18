package com.helmsail.databuddy.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 图执行服务(DataAgent 同款"托管订阅"模式):Controller 只建输出口(sink)并传入,
 * 本类自行订阅图、组装消息推送、登记运行(支持停止与断连清理)。
 * 新开还是续跑(同 threadId 存在检查点)由图执行引擎读 config 自行判断,此处不做分支
 */
@Service
public class GraphService {

	private final CompiledGraph compiledGraph;

	/** 进行中的流式运行登记:threadId → StreamRun(停止/清理用) */
	private final Map<String, StreamRun> runningStreams = new ConcurrentHashMap<>();

	public GraphService(CompiledGraph compiledGraph) {
		this.compiledGraph = compiledGraph;
	}

	// ---------- 对外 API ----------

	/** 流式执行:threadId 请求带了就用(续聊),没带生成(新会话);结果经本类组装后推入 sink */
	public void graphStreamProcess(Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink, GraphRequest request) {
		// ① 定会话号:带了就用,没带生成(新会话);写回请求对象供断连/停止兜底读取
		String threadId = StringUtils.hasText(request.getThreadId()) ? request.getThreadId()
				: UUID.randomUUID().toString();
		request.setThreadId(threadId);

		// ② 建进图门票:引擎靠它认人(按号查检查点→有旧账就续跑)和记账
		RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

		// ③ 接好输出管道:此刻还没有任何执行
		Flux<NodeOutput> outputs = compiledGraph.stream(Map.of(GraphKeys.INPUT, request.getQuery()), config);

		// ④ 建档登记:号 + 输出口放进花名册(为了让"停止"能按号找过来)
		StreamRun run = new StreamRun(threadId, sink);
		runningStreams.put(threadId, run);

		// ⑤ 订阅 = 点火:执行从这一刻才开始,在弹性线程池后台跑、不阻塞;
		//    图内节点是同步的(含 LLM 阻塞调用),不能占用 WebFlux event loop
		Disposable disposable = outputs.subscribeOn(Schedulers.boundedElastic())
			.subscribe(output -> emitNodeOutput(run, output),
					error -> emitStreamError(run, error),
					() -> emitStreamComplete(run));

		// ⑥ 停止开关到手,存进档案(停止时拉它)
		run.setDisposable(disposable);
	}

	/** 停止一次运行:取消图的订阅并移出登记(断连兜底与显式停止接口共用) */
	public void stopStreamProcessing(String threadId) {
		StreamRun run = runningStreams.remove(threadId);
		if (run != null) {
			run.stop();
		}
	}

	// ---------- 流事件处理(后续动作在此增补) ----------

	/** 节点完成 → 组一条 SSE 数据推入 sink(展示白名单:面向用户的最终回复,新增展示字段时在此登记) */
	private void emitNodeOutput(StreamRun run, NodeOutput output) {
		if (output.isSTART()) {
			return; // START 伪输出不播报
		}
		OverAllState state = output.state();
		Map<String, Object> data = new LinkedHashMap<>();
		state.value(GraphKeys.FINAL_ANSWER, String.class)
			.ifPresent(v -> data.put(GraphKeys.FINAL_ANSWER, v));

		GraphNodeResponse response = new GraphNodeResponse(run.getThreadId(), output.node(), data);
		run.getSink().tryEmitNext(ServerSentEvent.builder(response).build());
	}

	/** 流出错:推一条 error 数据后收口(以后:追踪收尾) */
	private void emitStreamError(StreamRun run, Throwable e) {
		String message = StringUtils.hasText(e.getMessage()) ? e.getMessage() : "执行失败";
		GraphNodeResponse response = new GraphNodeResponse(run.getThreadId(), "error",
				Map.of("message", message));
		run.getSink().tryEmitNext(ServerSentEvent.builder(response).build());
		run.getSink().tryEmitComplete();
		runningStreams.remove(run.getThreadId(), run);
	}

	/** 正常完成:收口并移出登记(以后:人工反馈等待判断 → 保留检查点等) */
	private void emitStreamComplete(StreamRun run) {
		run.getSink().tryEmitComplete();
		runningStreams.remove(run.getThreadId(), run);
	}

	// ---------- 流式运行 ----------

	/** 一次流式运行:会话号 + 输出口 + 停止开关 */
	@Getter
	@RequiredArgsConstructor
	private static class StreamRun {

		private final String threadId;

		private final Sinks.Many<ServerSentEvent<GraphNodeResponse>> sink;

		private volatile Disposable disposable;

		private volatile boolean stopped;

		void setDisposable(Disposable disposable) {
			this.disposable = disposable;
			if (stopped) {
				// 停止先于开关登记(竞态小窗口):补上取消
				disposable.dispose();
			}
		}

		void stop() {
			this.stopped = true;
			Disposable current = this.disposable;
			if (current != null) {
				current.dispose();
			}
		}

	}

}
