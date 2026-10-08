package com.helmsail.databuddy.middle.graph.sql;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanConstants;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * SQL 生成的出边分流器:写了终止语 → 终点;按 SQL_NEXT 去向——
 * validate(生成成功)→ SQL 校验;regenerate(空结果)→ 自跳重试;
 * replan(重试超限)→ 规划节点重写
 */
public class SqlGenerateDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.FINAL_ANSWER, String.class).orElse("");
		if (StringUtils.hasText(termination)) {
			return END;
		}
		String next = state.value(GraphKeys.SQL_NEXT, "validate");
		return switch (next) {
			case "end" -> END;
			case "replan" -> PlanConstants.PLANNER;
			case "regenerate" -> SqlConstants.SQL_GENERATE;
			default -> SqlConstants.SQL_VALIDATE;
		};
	}

}
