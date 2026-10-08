package com.helmsail.databuddy.bottom.vectorize;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 向量存在记录(vectorOverview 返回):按元数据层次组织——agent → 模型 → 知识类型;
 * 展示侧据此呈现,失效 agentId 的判定不在本层
 */
@Data
@AllArgsConstructor
public class VectorPresence {

	/** 来源 agent(metadata.agent_id;agent 已删则为待回收的失效锚点) */
	private long agentId;

	/** 该 agent 存在向量的模型分区(按模型名排序) */
	private List<ModelPresence> models;

	/** 单个模型分区:模型名 + 其下存在的知识类型 */
	@Data
	@AllArgsConstructor
	public static class ModelPresence {

		/** 生成该向量的模型名(metadata.embedding_model,即分区键) */
		private String model;

		/** 该分区存在的知识类型名(metadata.knowledge_type:TABLE / TERM / DOCUMENT / QA) */
		private List<String> knowledgeTypes;

	}

}
