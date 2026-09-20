package com.helmsail.databuddy.graph.report;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.plan.Plan;
import com.helmsail.databuddy.graph.plan.PlanStep;
import com.helmsail.databuddy.graph.plan.PlanUtils;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 报告生成节点(固定收尾):读计划(思路 + 步骤)与分步结果(STEP_RESULTS),产出 Markdown 报告写
 * FINAL_ANSWER,经既有 END 机制整段播报。计划里没有报告步——它由枢纽在步数走完后固定派发。
 * 每个结果值截断后进提示词(token 预算);阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class ReportGeneratorNode implements AsyncNodeAction {

	/** 单个分步结果进提示词的截断上限(字符) */
	private static final int RESULT_LIMIT = 4000;

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	public ReportGeneratorNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.reportGenerator", contextualName = "报告生成")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String canonical = state.value(GraphKeys.CANONICAL_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.REPORT_GENERATOR,
				Map.of("canonical_query", canonical, "plan_summary", planSummary(state), "results", resultsText(state)));
		String report = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
		log.info("报告生成完成: {} 字符", report == null ? 0 : report.length());
		return CompletableFuture.completedFuture(Map.of(GraphKeys.FINAL_ANSWER, report, GraphKeys.NODE_STATUS,
				"报告生成完成"));
	}

	/** 计划摘要:思路 + 各步指令(解析失败给原始 JSON 摘要) */
	private String planSummary(OverAllState state) {
		String planJson = state.value(GraphKeys.PLAN_JSON, String.class).orElse("");
		try {
			Plan plan = PlanUtils.parse(objectMapper, planJson);
			StringBuilder summary = new StringBuilder();
			if (StringUtils.hasText(plan.getThoughtProcess())) {
				summary.append("分析思路: ").append(plan.getThoughtProcess()).append('\n');
			}
			int index = 1;
			for (PlanStep step : plan.getExecutionPlan()) {
				summary.append("- 第 ").append(index++).append(" 步(").append(step.getToolToUse()).append("): ")
					.append(step.getInstruction()).append('\n');
			}
			return summary.toString().trim();
		}
		catch (RuntimeException e) {
			log.warn("报告节点计划解析失败: {}", e.getMessage());
			return NodeUtils.brief(planJson);
		}
	}

	/** 分步结果文本:按 step_N / step_N_analysis 键序拼接(每个值截断) */
	private String resultsText(OverAllState state) {
		Object raw = state.value(GraphKeys.STEP_RESULTS).orElse(null);
		if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
			return "(暂无执行结果)";
		}
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, String> entry : sortedEntries(map).entrySet()) {
			text.append("### ").append(entry.getKey()).append('\n').append(limit(entry.getValue())).append("\n\n");
		}
		return text.toString().trim();
	}

	/** 键序排序(step_1 < step_1_analysis < step_2;步数 ≤ 6,字符串序即自然序) */
	private Map<String, String> sortedEntries(Map<?, ?> map) {
		Map<String, String> sorted = new TreeMap<>();
		map.forEach((key, value) -> sorted.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
		return sorted;
	}

	/** 单值截断:超限截断并标注(防提示词爆量) */
	private String limit(String value) {
		if (value.length() <= RESULT_LIMIT) {
			return value;
		}
		return value.substring(0, RESULT_LIMIT) + "\n...(内容过长已截断)";
	}

}
