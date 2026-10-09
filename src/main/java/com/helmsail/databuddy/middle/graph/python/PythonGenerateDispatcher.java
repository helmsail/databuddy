package com.helmsail.databuddy.middle.graph.python;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanConstants;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * Python 生成的出边分流器:写了终止语 → 终点;按 PYTHON_NEXT 去向——
 * execute(生成成功)→ 执行节点;replan(重试超限)→ 规划节点重写
 */
public class PythonGenerateDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.Info.FINAL_ANSWER, String.class).orElse("");
		if (StringUtils.hasText(termination)) {
			return END;
		}
		String next = state.value(GraphKeys.Control.PYTHON_NEXT, "execute");
		return switch (next) {
			case "end" -> END;
			case "replan" -> PlanConstants.PLANNER;
			default -> PythonConstants.PYTHON_EXECUTE;
		};
	}

}
