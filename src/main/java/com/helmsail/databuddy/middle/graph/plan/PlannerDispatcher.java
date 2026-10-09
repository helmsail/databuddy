package com.helmsail.databuddy.middle.graph.plan;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 规划节点的出边分流器:写了终止语(本地重试超限)→ 终点;过厂计划 → 枢纽
 */
public class PlannerDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.Info.FINAL_ANSWER, String.class).orElse("");
		return StringUtils.hasText(termination) ? END : PlanConstants.PLAN_EXECUTOR;
	}

}
