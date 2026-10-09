package com.helmsail.databuddy.middle.graph.python;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanConstants;

/**
 * Python 分析的出边分流器:过检 → 枢纽(推进下一步);判定不一致 → 回生成节点(原因已在状态里,带动重写)
 */
public class PythonAnalyzeDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		boolean passed = state.value(GraphKeys.Control.PYTHON_PASSED, false);
		return passed ? PlanConstants.PLAN_EXECUTOR : PythonConstants.PYTHON_GENERATE;
	}

}
