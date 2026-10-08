package com.helmsail.databuddy.middle.graph.python;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanConstants;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * Python 执行的出边分流器:写了终止语 → 终点;按 PYTHON_NEXT 去向——
 * analyze(执行成功)→ 分析节点;regenerate(失败未超限)→ 回生成节点(带错误原文);
 * replan(重试超限)→ 规划节点重写
 */
public class PythonExecuteDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.FINAL_ANSWER, String.class).orElse("");
		if (StringUtils.hasText(termination)) {
			return END;
		}
		String next = state.value(GraphKeys.PYTHON_NEXT, "analyze");
		return switch (next) {
			case "end" -> END;
			case "replan" -> PlanConstants.PLANNER;
			case "regenerate" -> PythonConstants.PYTHON_GENERATE;
			default -> PythonConstants.PYTHON_ANALYZE;
		};
	}

}
