package com.helmsail.databuddy.middle.graph.python;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * Python 执行的出边分流器:写了终止语 → 终点;按 PYTHON_NEXT 去向——
 * analyze(执行成功)→ 分析闸;regenerate(失败)→ 回生成节点(带错误原文);超限与否由生成口统一裁决
 */
public class PythonExecuteDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String termination = state.value(GraphKeys.Info.FINAL_ANSWER, String.class).orElse("");
		if (StringUtils.hasText(termination)) {
			return END;
		}
		String next = state.value(GraphKeys.Control.PYTHON_NEXT, "analyze");
		return switch (next) {
			case "end" -> END;
			case "regenerate" -> PythonConstants.PYTHON_GENERATE;
			default -> PythonConstants.PYTHON_ANALYZE;
		};
	}

}
