package com.helmsail.databuddy.middle.graph.python;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.bottom.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanUtils;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Python 分析节点(Python 组闸):执行后审"数据与任务是否一致"——SQL 组的闸在执行前(审 SQL 文本),
 * Python 组的闸在执行后(审结果数据),这正是两组同构的排位差异。
 * 过检:解读文本写 STEP_RESULTS[step_N_analysis] 供报告引用,步号 +1、组内计数清零;
 * 判不一致:原因写 PYTHON_REPAIR_REASON 打回生成,并撤掉本步已写入的 STEP_RESULTS[step_N](防升级重规划后报告残留脏步);
 * 调用/解析失败按过检放行(质检不阻塞,同 SQL 校验的降级口径),过检而解读为空以占位语兜底(不因末段环节整轮失败)。
 * 阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class PythonAnalyzeNode implements AsyncNodeAction {

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	public PythonAnalyzeNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.pythonAnalyze", contextualName = "Python 分析")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String mainQuery = state.value(GraphKeys.Info.MAIN_QUERY, String.class)
			.orElse(state.value(GraphKeys.Info.INPUT, String.class).orElse(""));
		int step = NodeUtils.intOf(state, GraphKeys.Control.PLAN_STEP_NO, 1);
		Map<String, String> results = stepResults(state);
		String user = NodeUtils.renderPrompt(promptMapper, PythonConstants.PYTHON_ANALYZE,
				Map.of("task", PlanUtils.currentTaskOrFallback(objectMapper, state, "无"), "main_query", mainQuery,
						"python_result", judgmentInput(state, results, step)));
		return CompletableFuture.completedFuture(assess(user, step, results));
	}

	/** 判定并分流:一致/降级 → 过检收口;显式不一致 → 打回生成(带原因,撤本步结果) */
	private Map<String, Object> assess(String user, int step, Map<String, String> results) {
		try {
			String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
			JsonNode root = NodeUtils.parseJson(objectMapper, output);
			boolean consistent = root.path("consistent").asBoolean(false);
			String reason = root.path("reason").asText("");
			String analysis = root.path("analysis").asText("");
			log.info("Python 分析判定: consistent={}, reason={}", consistent, reason);
			if (!consistent) {
				log.warn("Python 分析判定不一致(第 {} 步): {}", step, reason);
				Map<String, String> purged = new HashMap<>(results);
				purged.remove("step_" + step);
				return Map.of(GraphKeys.Control.PYTHON_PASSED, false, GraphKeys.Control.PYTHON_REPAIR_REASON,
						"Python 分析判定不一致: " + reason, GraphKeys.Info.STEP_RESULTS, purged, GraphKeys.Info.PROGRESS,
						"Python 分析判定不一致:回生成重写");
			}
			return passed(step, results, analysis);
		}
		catch (RuntimeException e) {
			log.warn("Python 分析判定调用或解析失败,按过检放行(质检不阻塞): {}", e.getMessage());
			return passed(step, results, "");
		}
	}

	/** 过检:解读进报告、步号 +1、组内计数清零 */
	private Map<String, Object> passed(int step, Map<String, String> results, String analysis) {
		if (!StringUtils.hasText(analysis)) { // 空产出会丢报告小节,占位语兜底
			analysis = "本轮未产出分析文本。";
		}
		Map<String, String> updated = PlanUtils.withEntry(results, "step_" + step + "_analysis", analysis);
		log.info("Python 分析过检: 第 {} 步", step);
		return Map.of(GraphKeys.Control.PYTHON_PASSED, true, GraphKeys.Info.STEP_RESULTS, updated,
				GraphKeys.Control.PLAN_STEP_NO, step + 1, GraphKeys.Control.PYTHON_RETRY_COUNT, 0, GraphKeys.Info.PROGRESS,
				"Python 分析过检:数据与任务一致");
	}

	/** 判定素材:优先本步已写入的 step_N(含产物清单,防纯产物型步骤 stdout 为空时误判),回退 PYTHON_RESULT */
	private String judgmentInput(OverAllState state, Map<String, String> results, int step) {
		String stepText = results.get("step_" + step);
		if (StringUtils.hasText(stepText)) {
			return stepText;
		}
		String stdout = state.value(GraphKeys.Info.PYTHON_RESULT, String.class).orElse("");
		return StringUtils.hasText(stdout) ? stdout : "(无输出)";
	}

	/** 分步结果累积(整表回写:REPLACE 键语义) */
	private Map<String, String> stepResults(OverAllState state) {
		Object raw = state.value(GraphKeys.Info.STEP_RESULTS).orElse(null);
		if (raw instanceof Map<?, ?> map) {
			Map<String, String> results = new HashMap<>();
			map.forEach((key, value) -> results.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
			return results;
		}
		return Map.of();
	}

}
