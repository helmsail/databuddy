package com.helmsail.databuddy.graph.review;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.plan.PlanUtils;
import com.helmsail.databuddy.graph.util.NodeUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 计划确认节点(人工确认闸):计划执行前的唯一拦截点,靠图级 interruptBefore 静态中断挂起,
 * 恢复时由 GraphService 把决定写进 PLAN_REVIEW_DECISION 后从断点续跑到本节点。
 * 批准:关掉开关(后续步不再拦)转枢纽;否决:计数 +1 带意见打回规划重写,超限终止语收场。
 * 本节点零 LLM、零阻塞
 */
@Slf4j
@Component
public class PlanReviewNode implements AsyncNodeAction {

	/** 否决重写超限终止语(用户可见) */
	private static final String TERMINATION = "计划多次被否决,本轮分析已终止。你可以换一种更具体的问法重新提问。";

	@Override
	@Observed(name = "node.planReview", contextualName = "计划确认")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		Map<String, Object> decision = decision(state);
		if (decision.isEmpty()) {
			// 异常路径(未收到决定):回自身等待——静态中断会在再次到达本节点前挂起,不会忙转
			log.warn("计划确认节点未收到决定,回到等待");
			return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_NEXT, GraphKeys.PLAN_REVIEW));
		}
		Object approvedValue = decision.get("approved");
		boolean approved = approvedValue instanceof Boolean bool ? bool
				: Boolean.parseBoolean(String.valueOf(approvedValue));
		String feedback = String.valueOf(decision.getOrDefault("feedback", ""));
		if (approved) {
			log.info("计划已确认,放行执行");
			return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_REVIEW_ENABLED, false, GraphKeys.PLAN_NEXT,
					GraphKeys.PLAN_EXECUTOR, GraphKeys.NODE_STATUS, "计划已确认:开始执行"));
		}
		int count = NodeUtils.intOf(state, GraphKeys.PLAN_REPAIR_COUNT, 0) + 1;
		if (count > PlanUtils.MAX_PLAN_REPAIR) {
			log.warn("计划否决超限({} 次),终止", PlanUtils.MAX_PLAN_REPAIR);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.FINAL_ANSWER, TERMINATION, GraphKeys.NODE_STATUS,
					"计划被否决且超限:终止"));
		}
		log.info("计划被否决(第 {} 次),重新规划: {}", count, feedback);
		return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_REPAIR_COUNT, count, GraphKeys.PLAN_REPAIR_REASON,
				StringUtils.hasText(feedback) ? feedback : "用户否决了计划", GraphKeys.PLAN_STEP, 1, GraphKeys.PLAN_JSON,
				"", GraphKeys.PLAN_REVIEW_ENABLED, true, GraphKeys.PLAN_NEXT, GraphKeys.PLANNER, GraphKeys.NODE_STATUS,
				"计划已被否决:重新规划"));
	}

	/** 读确认决定(恢复时 updateState 写入;缺省空表) */
	private Map<String, Object> decision(OverAllState state) {
		Object raw = state.value(GraphKeys.PLAN_REVIEW_DECISION).orElse(null);
		if (raw instanceof Map<?, ?> map && !map.isEmpty()) {
			Map<String, Object> decision = new HashMap<>();
			map.forEach((key, value) -> decision.put(String.valueOf(key), value));
			return decision;
		}
		return Map.of();
	}

}
