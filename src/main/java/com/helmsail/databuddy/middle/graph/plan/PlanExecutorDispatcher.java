package com.helmsail.databuddy.middle.graph.plan;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 计划执行枢纽的出边分流器:按下一跳 PLAN_NEXT_NODE 走(枢纽每次必写:确认闸 / SQL 组 / Python 组 / 报告 / 终点)
 */
public class PlanExecutorDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		return state.value(GraphKeys.Control.PLAN_NEXT_NODE, END);
	}

}
