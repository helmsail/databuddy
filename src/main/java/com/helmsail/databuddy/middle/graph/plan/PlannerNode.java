package com.helmsail.databuddy.middle.graph.plan;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.bottom.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.GraphNodes;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 规划节点:数据链第六节点。把主查询转成"可执行的分步计划"(只含 SQL / Python 两类步骤,
 * 报告固定收尾不进计划)。生成本地循环:生成 → 当场解析 + 结构校验,不过则带问题与旧稿
 * 原地重生成(重试计数只是循环变量、不落键;超限写终止语),过厂才写 PLAN_JSON 与步号起点;
 * 重写场景(人工否决 / 执行组超限升级)读 PLAN_REPAIR_REASON 注入提示词。
 * 阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class PlannerNode implements AsyncNodeAction {

	/** 校验重写超限终止语(用户可见) */
	private static final String TERMINATION = "计划多次生成未通过校验,本轮分析无法继续。请调整问题描述后重试。";

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	public PlannerNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.planner", contextualName = "规划")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String mainQuery = state.value(GraphKeys.MAIN_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String schema = state.value(GraphKeys.SCHEMA, String.class).orElse("无");
		String knowledge = state.value(GraphKeys.KNOWLEDGE, String.class).orElse("无");
		boolean light = Boolean.TRUE.equals(state.value(GraphKeys.NL2SQL_ENABLED, false));
		String reason = state.value(GraphKeys.PLAN_REPAIR_REASON, String.class).orElse("");
		String previousPlan = state.value(GraphKeys.PLAN_JSON, String.class).orElse("");
		// 重写上下文:首次"(无)";外部打回时给原因 + 上一版计划(模型据此避开旧问题)
		String repairContext = "(无)";
		if (StringUtils.hasText(reason)) {
			repairContext = StringUtils.hasText(previousPlan) ? reason + "\n\n[上一版被否的计划]\n" + previousPlan
					: reason;
		}
		// 生成-校验本地循环:坏计划不出厂(计数只是循环变量,不落键);重试超限写终止语
		for (int attempt = 1; ; attempt++) {
			String user = NodeUtils.renderPrompt(promptMapper, GraphNodes.PLANNER,
					Map.of("main_query", mainQuery, "schema", schema, "knowledge", knowledge, "repair_context",
							repairContext, "nl2sql_enabled", light ? "轻档" : "常规"));
			String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
			String planJson = NodeUtils.stripFence(output);
			log.info("计划生成完成(第 {} 次尝试): {}", attempt, NodeUtils.brief(planJson));
			Plan plan = null;
			String invalid;
			try {
				plan = PlanUtils.parse(objectMapper, planJson);
				invalid = PlanUtils.validate(plan);
			}
			catch (RuntimeException e) {
				invalid = "计划无法解析: " + e.getMessage();
			}
			if (invalid == null) {
				// 过厂:步号重置为 1(重写场景旧步号作废),写计划
				return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_JSON, planJson, GraphKeys.PLAN_STEP_NO, 1,
						GraphKeys.PROGRESS, "规划完成:共 " + plan.getPlanSteps().size() + " 步"));
			}
			if (attempt > PlanUtils.MAX_PLAN_REPAIR) {
				log.error("计划重写超限({} 次),终止: {}", PlanUtils.MAX_PLAN_REPAIR, invalid);
				return CompletableFuture.completedFuture(Map.of(GraphKeys.FINAL_ANSWER, TERMINATION, GraphKeys.PROGRESS,
						"计划校验失败且重写超限:终止"));
			}
			log.warn("计划校验未通过(第 {} 次重写): {}", attempt, invalid);
			// 下一轮带问题与旧稿重写(带原文改)
			repairContext = invalid + "\n\n[上一版被否的计划]\n" + planJson;
		}
	}

}
