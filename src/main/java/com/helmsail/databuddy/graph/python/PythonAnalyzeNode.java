package com.helmsail.databuddy.graph.python;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.plan.PlanUtils;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Python 分析节点(Python 组质检):执行后审"结果对不对"——SQL 组的反思在执行前(SQL 文本),
 * Python 组的反思在执行后(产出结果),这正是两组对称的质检点差异。
 * 分析文本写 PYTHON_ANALYSIS 与 STEP_RESULTS[step_N_analysis] 供报告引用;
 * 步号在此 +1(SQL 组的步号推进在 SQL 执行成功时,各自收口)。
 * 阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class PythonAnalyzeNode implements AsyncNodeAction {

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	public PythonAnalyzeNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
	}

	@Override
	@Observed(name = "node.pythonAnalyze", contextualName = "Python 分析")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String canonical = state.value(GraphKeys.CANONICAL_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String pythonOutput = state.value(GraphKeys.PYTHON_RESULT, String.class).orElse("(无输出)");
		String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.PYTHON_ANALYZE,
				Map.of("canonical_query", canonical, "python_output", pythonOutput));
		String analysis = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
		int step = NodeUtils.intOf(state, GraphKeys.PLAN_STEP, 1);
		Map<String, String> results = PlanUtils.withEntry(stepResults(state), "step_" + step + "_analysis", analysis);
		log.info("Python 分析完成: 第 {} 步", step);
		return CompletableFuture.completedFuture(Map.of(GraphKeys.PYTHON_ANALYSIS, analysis, GraphKeys.STEP_RESULTS, results,
				GraphKeys.PLAN_STEP, step + 1, GraphKeys.PYTHON_ATTEMPT, 0, GraphKeys.NODE_STATUS, "Python 分析完成"));
	}

	/** 分步结果累积(整表回写:REPLACE 键语义) */
	private Map<String, String> stepResults(OverAllState state) {
		Object raw = state.value(GraphKeys.STEP_RESULTS).orElse(null);
		if (raw instanceof Map<?, ?> map) {
			Map<String, String> results = new HashMap<>();
			map.forEach((key, value) -> results.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
			return results;
		}
		return Map.of();
	}

}
