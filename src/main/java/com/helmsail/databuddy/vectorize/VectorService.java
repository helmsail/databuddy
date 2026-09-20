package com.helmsail.databuddy.vectorize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

	/** 删除某来源的全部向量(该来源改 / 删时调用);未配嵌入模型时跳过(见 embeddingAvailable 注释) */
	public void deleteBySource(long agentId, IndexSourceType sourceType, long sourceId) {
		if (!embeddingAvailable()) {
			return;
		}
		vectorStore.delete(agentFilter(agentId) + " && " + VectorMetadata.SOURCE_TYPE + " == '" + sourceType.name()
				+ "' && " + VectorMetadata.SOURCE_ID + " == " + sourceId);
	}

	/** 删除某 agent 的全部向量(删 agent 级联用);未配嵌入模型时跳过 */
	public void deleteByAgent(long agentId) {
		if (!embeddingAvailable()) {
			return;
		}
		vectorStore.delete(agentFilter(agentId));
	}

	/**
	 * 嵌入模型是否可用:未配置时跳过向量清理——SimpleVectorStore 的删除内部也要用嵌入算文档键,硬调会把
	 * "删文档/术语/绑表/智能体"全堵死;而内存实现下,没有模型就不可能入过向量,跳过等价于空删
	 */
	private boolean embeddingAvailable() {
		if (!StringUtils.hasText(aiModelServiceFactory.getEmbeddingModelName())) {
			log.warn("未配置 EMBEDDING 模型,跳过向量清理(内存向量库此时必为空)");
			return false;
		}
		return true;
	}

	/** 按 agent 检索(跨全部来源类型);命中块自带 metadata 与相似度,回源由调用方按 source_type/source_id 完成 */
	public List<Document> search(long agentId, String query, int topK) {
		return search(agentId, query, topK, null);
	}

	/** 按 agent + 指定来源类型检索(sourceTypes 空 = 全部来源) */
	public List<Document> search(long agentId, String query, int topK, Collection<IndexSourceType> sourceTypes) {
		return vectorStore.similaritySearch(SearchRequest.builder()
			.query(query)
			.topK(topK)
			.filterExpression(filterOf(agentId, sourceTypes))
			.build());
	}

	/** 过滤表达式组装(检索与删除共用):agent 锚点 + 可选来源类型 OR 组 */
	private String filterOf(long agentId, Collection<IndexSourceType> sourceTypes) {
		String filter = agentFilter(agentId);
		if (sourceTypes == null || sourceTypes.isEmpty()) {
			return filter;
		}
		String in = sourceTypes.stream()
			.map(type -> VectorMetadata.SOURCE_TYPE + " == '" + type.name() + "'")
			.collect(Collectors.joining(" || "));
		return filter + " && (" + in + ")";
	}

	/** agent 过滤表达式(删除与检索共用) */
	private String agentFilter(long agentId) {
		return VectorMetadata.AGENT_ID + " == " + agentId;
	}

}
