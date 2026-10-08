package com.helmsail.databuddy.middle.graph.report;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.bottom.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.Plan;
import com.helmsail.databuddy.middle.graph.plan.PlanStep;
import com.helmsail.databuddy.middle.graph.plan.PlanUtils;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.memory.AgentMemoryTools;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;

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

	/** 记忆工具:分析收尾时沉淀稳定口径 / 规则(不含本次数据结论) */
	private final AgentMemoryTools agentMemoryTools;

	public ReportGeneratorNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper, AgentMemoryTools agentMemoryTools) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
		this.agentMemoryTools = agentMemoryTools;
	}

	@Override
	@Observed(name = "node.reportGenerator", contextualName = "报告生成")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String mainQuery = state.value(GraphKeys.MAIN_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String agentMemory = state.value(GraphKeys.AGENT_MEMORY, String.class).orElse("(无)");
		String user = NodeUtils.renderPrompt(promptMapper, ReportConstants.REPORT_GENERATOR, Map.of("main_query", mainQuery,
				"plan_summary", planSummary(state), "step_results", resultsText(state), "agent_memory", agentMemory));
		long agentId = NodeUtils.longOf(state, GraphKeys.AGENT_ID);
		String report = aiModelServiceFactory.getChatClient()
			.prompt()
			.user(user)
			.tools(agentMemoryTools)
			.toolContext(Map.of(AgentMemoryTools.AGENT_ID_KEY, agentId))
			.call()
			.content();
		if (!StringUtils.hasText(report)) { // 模型只调工具(如沉淀记忆)或空产出时 content() 为 null:占位语收尾,不因末段环节整轮失败
			log.warn("报告生成返回空文本,以占位语收尾");
			report = "本轮未产出报告正文,可查看上方的执行过程与结果。";
		}
		log.info("报告生成完成: {} 字符", report.length());
		return CompletableFuture.completedFuture(Map.of(GraphKeys.FINAL_ANSWER, report, GraphKeys.PROGRESS,
				"报告生成完成"));
	}

	/** 计划摘要:标题 + 各步任务(解析失败给原始 JSON 摘要) */
	private String planSummary(OverAllState state) {
		String planJson = state.value(GraphKeys.PLAN_JSON, String.class).orElse("");
		try {
			Plan plan = PlanUtils.parse(objectMapper, planJson);
			StringBuilder summary = new StringBuilder();
			if (StringUtils.hasText(plan.getPlanTitle())) {
				summary.append("计划标题: ").append(plan.getPlanTitle()).append('\n');
			}
			int index = 1;
			for (PlanStep step : plan.getPlanSteps()) {
				summary.append("- 第 ").append(index++).append(" 步(").append(step.getSelectGroup()).append("): ")
				.append(step.getTask()).append('\n');
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
