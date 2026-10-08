package com.helmsail.databuddy.graph.intent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.helmsail.databuddy.graph.GraphKeys;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 意图识别节点的出边分流器:读分类结果决定下一跳——仅 data_analysis → 知识召回节点;
 * 其余(chat / 缺失兜底) → 终点,不退数据链。
 * 分流与节点同包维护,图装配只引用本类(对齐参考实现的 dispatcher 习惯)
 */
public class IntentRecognitionDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		IntentType type = IntentType.from(state.value(IntentKeys.CLASSIFICATION, String.class).orElse(""));
		return type == IntentType.DATA_ANALYSIS ? GraphKeys.KNOWLEDGE_RECALL : END;
	}

}
