package com.helmsail.databuddy.vectorize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.vectorize.splitter.DocumentSplitterFactory;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;

/**
 * 向量服务:内容向量化的唯一出入口——原文 + 策略进,包内完成切分 → 向量化 → 落库,并负责删除 / 检索。
 * metadata 字段约定统一收在 VectorMetadata(隔离过滤 / 回源锚点 / 重建判断),本类负责写入与过滤表达式构造。
 * index 先删同源旧向量再写入:重复入库幂等,分块数变少也不会留僵尸块
 */
@Slf4j
@Service
public class VectorService {

	private final VectorStore vectorStore;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final DocumentSplitterFactory documentSplitterFactory;

	public VectorService(VectorStore vectorStore, AiModelServiceFactory aiModelServiceFactory,
			DocumentSplitterFactory documentSplitterFactory) {
		this.vectorStore = vectorStore;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.documentSplitterFactory = documentSplitterFactory;
	}

	/** 写入某来源的原始内容:按策略切分 → 向量化 → 落库(先删同源旧向量,幂等);空内容直接返回 */
	public void index(long agentId, IndexSourceType sourceType, long sourceId, SplitterType splitterType,
			String rawContent) {
		List<String> chunks = documentSplitterFactory.get(splitterType).split(rawContent);
		if (chunks.isEmpty()) {
			return;
		}
		deleteBySource(agentId, sourceType, sourceId);
		String modelName = aiModelServiceFactory.getEmbeddingModelName();
		List<Document> documents = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			Map<String, Object> metadata = new HashMap<>();
			metadata.put(VectorMetadata.AGENT_ID, agentId);
			metadata.put(VectorMetadata.SOURCE_TYPE, sourceType.name());
			metadata.put(VectorMetadata.SOURCE_ID, sourceId);
			metadata.put(VectorMetadata.CHUNK_INDEX, i);
			if (StringUtils.hasText(modelName)) {
				metadata.put(VectorMetadata.EMBEDDING_MODEL, modelName);
			}
			documents.add(new Document(chunks.get(i), metadata));
		}
		vectorStore.add(documents);
		log.info("向量写入: agent={}, source={}#{}, chunks={}", agentId, sourceType, sourceId, chunks.size());
	}

	/** 删除某来源的全部向量(该来源改 / 删时调用) */
	public void deleteBySource(long agentId, IndexSourceType sourceType, long sourceId) {
		vectorStore.delete(agentFilter(agentId) + " && " + VectorMetadata.SOURCE_TYPE + " == '" + sourceType.name()
				+ "' && " + VectorMetadata.SOURCE_ID + " == " + sourceId);
	}

	/** 删除某 agent 的全部向量(删 agent 级联用) */
	public void deleteByAgent(long agentId) {
		vectorStore.delete(agentFilter(agentId));
	}

	/** 按 agent 检索(跨全部来源类型);命中块自带 metadata 与相似度,回源由调用方按 source_type/source_id 完成 */
	public List<Document> search(long agentId, String query, int topK) {
		return vectorStore.similaritySearch(SearchRequest.builder()
			.query(query)
			.topK(topK)
			.filterExpression(agentFilter(agentId))
			.build());
	}

	/** agent 过滤表达式(删除与检索共用) */
	private String agentFilter(long agentId) {
		return VectorMetadata.AGENT_ID + " == " + agentId;
	}

}
