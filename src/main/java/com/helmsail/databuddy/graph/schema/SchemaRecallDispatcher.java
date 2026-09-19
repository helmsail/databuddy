package com.helmsail.databuddy.graph.schema;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * Schema 召回的出边分流器:写了终止语(未命中,节点已写 FINAL_ANSWER)→ 终点;否则 → 表关系节点。
 * 判据用"终止语是否已写",与节点侧终止机制同源(不依赖表名解析是否成功)
 */
public class SchemaRecallDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String terminated = state.value(GraphKeys.FINAL_ANSWER, String.class).orElse("");
		return StringUtils.hasText(terminated) ? END : GraphKeys.TABLE_RELATION;
	}

}
