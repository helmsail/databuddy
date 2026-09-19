package com.helmsail.databuddy.graph.intent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 意图识别节点的出边分流器:读分类结果决定下一跳——chat → 终点;
 * 其余(仅 data_analysis,分类值已由节点侧校验) → 知识召回节点。
 * 分流与节点同包维护,图装配只引用本类(对齐参考实现的 dispatcher 习惯)
 */
public class IntentRecognitionDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		String classification = state.value(GraphKeys.CLASSIFICATION, String.class).orElse(GraphKeys.INTENT_CHAT);
		if (GraphKeys.INTENT_CHAT.equals(classification)) {
			return END;
		}
		return GraphKeys.KNOWLEDGE_RECALL;
	}

}
