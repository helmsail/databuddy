package com.helmsail.databuddy.graph.review;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 计划确认节点的出边分流器:写了终止语(否决超限)→ 终点;批准 → 计划执行枢纽;
 * 否决 → 规划节点重写;未收到决定(异常路径)→ 回自身等待(引擎静态中断会再次挂起)
 */
public class PlanReviewDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.FINAL_ANSWER, String.class).orElse("");
		if (StringUtils.hasText(termination)) {
			return END;
		}
		String next = state.value(GraphKeys.PLAN_NEXT, "");
		if (GraphKeys.PLAN_EXECUTOR.equals(next)) {
			return GraphKeys.PLAN_EXECUTOR;
		}
		if (GraphKeys.PLAN_REVIEW.equals(next)) {
			return GraphKeys.PLAN_REVIEW;
		}
		return GraphKeys.PLANNER;
	}

}
