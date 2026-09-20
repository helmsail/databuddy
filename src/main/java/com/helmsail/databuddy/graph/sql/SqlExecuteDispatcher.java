package com.helmsail.databuddy.graph.sql;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * SQL 执行的出边分流器:写了终止语 → 终点;按 SQL_NEXT 去向——
 * hub(执行成功)→ 计划执行枢纽(推进下一步);regenerate(执行失败)→ 回生成节点(带错误原文)
 */
public class SqlExecuteDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.FINAL_ANSWER, String.class).orElse("");
		if (StringUtils.hasText(termination)) {
			return END;
		}
		String next = state.value(GraphKeys.SQL_NEXT, "hub");
		return switch (next) {
			case "end" -> END;
			case "hub" -> GraphKeys.PLAN_EXECUTOR;
			default -> GraphKeys.SQL_GENERATE;
		};
	}

}
