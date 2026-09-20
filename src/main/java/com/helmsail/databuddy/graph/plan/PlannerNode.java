package com.helmsail.databuddy.graph.plan;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 规划节点:数据链第六节点。把规范查询转成"可执行的分步计划"(只含 SQL / Python 两类步骤,
 * 报告固定收尾不进计划),写 PLAN_JSON 与步号起点;重写场景(校验不过/人工否决/执行组超限升级)
 * 读 PLAN_REPAIR_REASON 注入提示词,并把上一版计划一并交给模型参考。
 * 计划的结构校验不在本节点(统一在枢纽第一步前做一次);阻塞的 LLM 调用发生在
 * 图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class PlannerNode implements AsyncNodeAction {

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
		String canonical = state.value(GraphKeys.CANONICAL_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String schema = state.value(GraphKeys.SCHEMA, String.class).orElse("无");
		String knowledge = state.value(GraphKeys.KNOWLEDGE, String.class).orElse("无");
		String reason = state.value(GraphKeys.PLAN_REPAIR_REASON, String.class).orElse("");
		String previousPlan = state.value(GraphKeys.PLAN_JSON, String.class).orElse("");
		String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.PLANNER,
				Map.of("canonical_query", canonical, "schema", schema, "knowledge", knowledge, "repair_context",
						repairContext(reason, previousPlan)));
		String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
		String planJson = NodeUtils.stripFence(output);
		log.info("计划生成完成: {}", NodeUtils.brief(planJson));
		// 步号重置为 1:新计划从头执行(重写场景旧步号作废)
		return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_JSON, planJson, GraphKeys.PLAN_STEP, 1,
				GraphKeys.NODE_STATUS, note(planJson)));
	}

	/** 重写上下文:首次为空;重写时给出原因 + 上一版计划(模型据此避开旧问题) */
	private String repairContext(String reason, String previousPlan) {
		if (!StringUtils.hasText(reason)) {
			return "(无)";
		}
		String context = reason;
		if (StringUtils.hasText(previousPlan)) {
			context += "\n\n[上一版被否的计划]\n" + previousPlan;
		}
		return context;
	}

	/** 过程播报:解析成功报步数,失败只报完成(结构校验在枢纽兜底) */
	private String note(String planJson) {
		try {
			Plan plan = PlanUtils.parse(objectMapper, planJson);
			int size = plan.getExecutionPlan() == null ? 0 : plan.getExecutionPlan().size();
			return "规划完成:共 " + size + " 步";
		}
		catch (Exception e) {
			log.warn("计划解析失败(留待枢纽校验): {}", e.getMessage());
			return "规划完成:计划待校验";
		}
	}

}
