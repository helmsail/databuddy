package com.helmsail.databuddy.graph.plan;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 计划执行枢纽的出边分流器:三种去向——
 * 写了终止语(重写超限)→ 终点;计划校验通过 → 按 PLAN_NEXT 派活(节点 ID 在装配侧声明);
 * 校验不过 → 回规划节点重写
 */
public class PlanExecutorDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.FINAL_ANSWER, String.class).orElse("");
		if (StringUtils.hasText(termination)) {
			return END;
		}
		boolean valid = state.value(GraphKeys.PLAN_VALID, false);
		if (valid) {
			return state.value(GraphKeys.PLAN_NEXT, END);
		}
		return GraphKeys.PLANNER;
	}

}
