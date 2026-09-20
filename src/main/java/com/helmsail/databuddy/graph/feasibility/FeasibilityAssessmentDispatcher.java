package com.helmsail.databuddy.graph.feasibility;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 可行性评估的出边分流器:写了终止语(需要澄清,节点已写 FINAL_ANSWER)→ 终点;
 * 否则(可分析 / 降级放行)→ 规划节点。判据与节点侧终止机制同源
 */
public class FeasibilityAssessmentDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String clarification = state.value(GraphKeys.FINAL_ANSWER, String.class).orElse("");
		return StringUtils.hasText(clarification) ? END : GraphKeys.PLANNER;
	}

}
