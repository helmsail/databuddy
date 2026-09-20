package com.helmsail.databuddy.graph.sql;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.graph.GraphKeys;

/**
 * 语义一致性的出边分流器:通过 → SQL 执行节点;未通过 → 回 SQL 生成节点(原因已在状态里,带动重写)
 */
public class SemanticConsistencyDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		boolean passed = state.value(GraphKeys.SEMANTIC_PASSED, false);
		return passed ? GraphKeys.SQL_EXECUTE : GraphKeys.SQL_GENERATE;
	}

}
