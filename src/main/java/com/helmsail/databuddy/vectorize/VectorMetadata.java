package com.helmsail.databuddy.vectorize;

/**
 * 向量 metadata 字段词汇表:写入端(过滤表达式构造)与回源端(据 SOURCE_TYPE + SOURCE_ID 定位原始行)共用的唯一约定。
 * 后续增删字段只改这里一处
 */
public final class VectorMetadata {

	/** 所属 agent(隔离与过滤) */
	public static final String AGENT_ID = "agent_id";

	/** 来源类型(IndexSourceType.name()),与 SOURCE_ID 构成回源锚点 */
	public static final String SOURCE_TYPE = "source_type";

	/** 来源行主键 */
	public static final String SOURCE_ID = "source_id";

	/** 分块序号(重建与调试用) */
	public static final String CHUNK_INDEX = "chunk_index";

	/** 写入时的嵌入模型名(模型切换后判断是否需要重建) */
	public static final String EMBEDDING_MODEL = "embedding_model";

	private VectorMetadata() {
	}

}
