package com.helmsail.databuddy.vectorize;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.helmsail.databuddy.aimodel.AiModelConfigService;
import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.vectorize.splitter.DocumentSplitterFactory;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;

/**
 * 向量服务:内容向量化的唯一出入口——原文 + 策略进,包内完成切分 → 向量化 → 落库,并负责删除 / 检索。
 * metadata 字段约定统一收在 VectorMetadata(隔离过滤 / 回源锚点),本类负责写入与过滤表达式构造。
 * index 先删同源旧向量再写入:重复入库幂等,分块数变少也不会留僵尸块。
 * 向量按嵌入模型分区(metadata 记模型名):检索只在本模型分区内进行——切换模型后新旧向量互不串用,
 * 切回原模型即恢复;模型配置删除后的孤儿分区由 cleanupOrphanModels 回收(定时 + 手动触发)
 */
@Slf4j
@Service
public class VectorService {

	/** 删除前检索用的占位查询文本(结果由过滤条件限定,相似度仅用于排序不参与筛选) */
	private static final String CLEANUP_QUERY = "cleanup";

	/** 按过滤删除前的单次读取上限(同源块数超过此值属异常数据,按上限截断) */
	private static final int CLEANUP_TOP_K = 10000;

	/** 分区盘点的读取上限:全量语义,孤儿识别不因截断而遗漏 */
	private static final int PARTITION_SCAN_TOP_K = Integer.MAX_VALUE;

	private final VectorStore vectorStore;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final DocumentSplitterFactory documentSplitterFactory;

	private final AiModelConfigService aiModelConfigService;

	public VectorService(VectorStore vectorStore, AiModelServiceFactory aiModelServiceFactory,
			DocumentSplitterFactory documentSplitterFactory, AiModelConfigService aiModelConfigService) {
		this.vectorStore = vectorStore;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.documentSplitterFactory = documentSplitterFactory;
		this.aiModelConfigService = aiModelConfigService;
	}

	/** 写入某来源的原始内容:按策略切分 → 向量化 → 落库(先删同源旧向量,限本模型分区,幂等);空内容直接返回 */
	public void index(long agentId, IndexSourceType sourceType, long sourceId, SplitterType splitterType,
			String rawContent) {
		List<String> chunks = documentSplitterFactory.get(splitterType).split(rawContent);
		if (chunks.isEmpty()) {
			return;
		}
		deleteByFilter(agentFilter(agentId) + " && " + sourceFilter(sourceType, sourceId) + " && " + modelFilter());
		List<Document> documents = new ArrayList<>(chunks.size());
		for (String chunk : chunks) {
			Map<String, Object> metadata = new HashMap<>();
			metadata.put(VectorMetadata.AGENT_ID, agentId);
			metadata.put(VectorMetadata.SOURCE_TYPE, sourceType.name());
			metadata.put(VectorMetadata.SOURCE_ID, sourceId);
			metadata.put(VectorMetadata.EMBEDDING_MODEL, aiModelServiceFactory.getEmbeddingModelName());
			documents.add(new Document(chunk, metadata));
		}
		vectorStore.add(documents);
		log.info("向量写入: agent={}, source={}#{}, chunks={}", agentId, sourceType, sourceId, chunks.size());
	}

	/** 删除某来源的全部向量(该来源删时调用;跨全部模型分区——对象已删,各模型副本都成垃圾);未配嵌入模型时跳过 */
	public void deleteBySource(long agentId, IndexSourceType sourceType, long sourceId) {
		if (!embeddingAvailable()) {
			return;
		}
		deleteByFilter(agentFilter(agentId) + " && " + sourceFilter(sourceType, sourceId));
	}

	/** 删除某 agent 的全部向量(删 agent 级联用;跨全部模型分区);未配嵌入模型时跳过 */
	public void deleteByAgent(long agentId) {
		if (!embeddingAvailable()) {
			return;
		}
		deleteByFilter(agentFilter(agentId));
	}

	/**
	 * 清理孤儿分区:某模型名在模型配置(含未激活备用行)中已不存在 → 其向量永远不会再生效,可安全回收。
	 * 流程:取存活名单 → 盘点库中现存分区 → 差集为孤儿 → 删除前重查一次存活名单(双重检查,
	 * 防"判定与执行"间隔内配置复活被误清)→ 按分区删除(快照语义:判定后新写入的向量不受影响);
	 * 未配嵌入模型时跳过(占位检索需要嵌入);失败由调用方记录,下轮幂等重扫。返回清理的分区数
	 */
	public int cleanupOrphanModels() {
		if (!embeddingAvailable()) {
			return 0;
		}
		Set<String> alive = new HashSet<>(aiModelConfigService.embeddingModelNames());
		List<String> orphans = existingModelNames().stream()
			.filter(model -> !alive.contains(model))
			.toList();
		if (orphans.isEmpty()) {
			return 0;
		}
		Set<String> aliveNow = new HashSet<>(aiModelConfigService.embeddingModelNames());
		int cleaned = 0;
		for (String model : orphans) {
			if (aliveNow.contains(model)) {
				continue;
			}
			deleteByFilter(modelFilterOf(model));
			cleaned++;
			log.info("孤儿向量分区已清理: model={}", model);
		}
		return cleaned;
	}

	/** 盘点库中现存的模型分区名(占位检索拉全量 metadata;低频清理操作,全扫可接受) */
	private Set<String> existingModelNames() {
		List<Document> all = vectorStore.similaritySearch(SearchRequest.builder()
			.query(CLEANUP_QUERY)
			.topK(PARTITION_SCAN_TOP_K)
			.similarityThreshold(0.0)
			.build());
		if (all == null) {
			return Set.of();
		}
		return all.stream()
			.map(doc -> doc.getMetadata().get(VectorMetadata.EMBEDDING_MODEL))
			.filter(Objects::nonNull)
			.map(Object::toString)
			.collect(Collectors.toSet());
	}

	/**
	 * 按过滤条件删除向量:SimpleVectorStore 未实现 doDelete(Filter.Expression)(父类默认抛
	 * UnsupportedOperationException,已实测),因此改为"先按条件检索取回文档 id、再按 id 删除";
	 * 检索用占位 query + 阈值 0,结果完全由过滤条件决定(仅多一次嵌入调用,删除为低频操作)
	 */
	private void deleteByFilter(String filterExpression) {
		List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
			.query(CLEANUP_QUERY)
			.topK(CLEANUP_TOP_K)
			.similarityThreshold(0.0)
			.filterExpression(filterExpression)
			.build());
		if (hits == null || hits.isEmpty()) {
			return;
		}
		vectorStore.delete(hits.stream().map(Document::getId).toList());
	}

	/**
	 * 嵌入模型是否可用:未配置时跳过向量清理——SimpleVectorStore 的删除内部也要用嵌入算文档键,硬调会把
	 * "删文档/术语/绑表/智能体"全堵死;而内存实现下,没有模型就不可能入过向量,跳过等价于空删。
	 * 判定依赖工厂契约:未配置 EMBEDDING 时 getEmbeddingModel 抛业务异常(窄捕获,勿扩为 Exception)
	 */
	private boolean embeddingAvailable() {
		try {
			aiModelServiceFactory.getEmbeddingModel();
			return true;
		}
		catch (BusinessException e) {
			log.warn("未配置 EMBEDDING 模型,跳过向量清理(内存向量库此时必为空)");
			return false;
		}
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

	/** 检索过滤表达式:agent 锚点 + 当前模型分区 + 可选来源类型 OR 组(旧模型向量被过滤,不参与本次计算) */
	private String filterOf(long agentId, Collection<IndexSourceType> sourceTypes) {
		String filter = agentFilter(agentId) + " && " + modelFilter();
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

	/** 来源过滤表达式(回源锚点:类型 + 行主键) */
	private String sourceFilter(IndexSourceType sourceType, long sourceId) {
		return VectorMetadata.SOURCE_TYPE + " == '" + sourceType.name() + "' && " + VectorMetadata.SOURCE_ID + " == "
				+ sourceId;
	}

	/** 当前模型分区过滤表达式 */
	private String modelFilter() {
		return modelFilterOf(aiModelServiceFactory.getEmbeddingModelName());
	}

	/** 按模型名构造分区过滤表达式 */
	private String modelFilterOf(String modelName) {
		return VectorMetadata.EMBEDDING_MODEL + " == '" + modelName + "'";
	}

}
