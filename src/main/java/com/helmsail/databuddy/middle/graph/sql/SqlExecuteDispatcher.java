package com.helmsail.databuddy.middle.graph.sql;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanConstants;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * SQL 执行的出边分流器:写了终止语 → 终点;按 SQL_NEXT 去向——
 * hub(执行成功)→ 计划执行枢纽(推进下一步);regenerate(执行失败)→ 回生成节点(带错误原文)
 */
public class SqlExecuteDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.Info.FINAL_ANSWER, String.class).orElse("");
		if (StringUtils.hasText(termination)) {
			return END;
		}
		String next = state.value(GraphKeys.Control.SQL_NEXT, "hub");
		return switch (next) {
			case "end" -> END;
			case "hub" -> PlanConstants.PLAN_EXECUTOR;
			default -> SqlConstants.SQL_GENERATE;
		};
	}

}
