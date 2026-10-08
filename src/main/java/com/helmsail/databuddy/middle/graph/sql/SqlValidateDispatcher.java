package com.helmsail.databuddy.middle.graph.sql;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.middle.graph.GraphKeys;

/**
 * SQL 校验的出边分流器:通过 → SQL 执行节点;未通过 → 回 SQL 生成节点(原因已在状态里,带动重写)
 */
public class SqlValidateDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		boolean passed = state.value(GraphKeys.SQL_PASSED, false);
		return passed ? SqlConstants.SQL_EXECUTE : SqlConstants.SQL_GENERATE;
	}

}
