package com.helmsail.databuddy.vectorize;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.vectorize.splitter.DocumentSplitterFactory;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;

/**
 * 向量服务:内容向量化的唯一出入口——原文 + 策略进,包内完成切分 → 向量化 → 落库,并负责删除 / 检索 / 盘点。
 * 元数据构成一棵树:agent → 嵌入模型 → 知识类型 → 知识条目;向量按模型分区,写删只作用现役分区——
 * 切换模型后新旧互不串用、切回原模型即恢复,历史分区为回滚快照,报废由运维台整分区回收。
 * 字段约定统一收在 VectorMetadata,过滤表达式由 filterOf 按层次前缀拼装(空层短路);对外四个通用操作:
 * index(写入)/ deleteByDims(按前缀删除)/ search(检索,单类型) / vectorOverview(元数据层次盘点),
 * 业务语义由上层按前缀组合,本服务不做自动删除
 */
@Slf4j
@Service
public class VectorService {

	/** 占位查询文本(删除与盘点共用:结果由过滤条件或全量语义限定,相似度仅用于排序) */
	private static final String PLACEHOLDER_QUERY = "placeholder";

	/** 按过滤删除的单批读取上限:层空 = 整棵子树时匹配集可能很大(级联 / 幽灵数据),循环分批直至清空 */
	private static final int DELETE_BATCH_SIZE = 10000;

	/** 分区盘点的读取上限:全量语义,不因截断而遗漏 */
	private static final int PARTITION_SCAN_TOP_K = Integer.MAX_VALUE;

	private final VectorStore vectorStore;

	private final DelegatingEmbeddingModel embeddingModel;

	private final DocumentSplitterFactory documentSplitterFactory;

	public VectorService(VectorStore vectorStore, DelegatingEmbeddingModel embeddingModel,
			DocumentSplitterFactory documentSplitterFactory) {
		this.vectorStore = vectorStore;
		this.embeddingModel = embeddingModel;
		this.documentSplitterFactory = documentSplitterFactory;
	}

	/**
	 * 写入某知识条目:按切分类型取策略切分 → 存入前先删同条目旧向量(限现役分区——重复入库幂等,
	 * 分块数变少也不留僵尸块)→ 填四键元数据落库;空内容直接返回
	 */
	public void index(long agentId, KnowledgeType knowledgeType, long knowledgeId, SplitterType splitterType,
			String rawContent) {
		List<String> chunks = documentSplitterFactory.get(splitterType).split(rawContent);
		if (chunks.isEmpty()) {
			return;
		}
		String modelName = embeddingModel.modelName();
		deleteByDims(agentId, modelName, knowledgeType, knowledgeId);
		// 四键元数据对全部块相同:一份不可变 Map 共享,Document 构造时各自拷贝
		Map<String, Object> metadata = Map.of(
				VectorMetadata.AGENT_ID, agentId,
				VectorMetadata.KNOWLEDGE_TYPE, knowledgeType.name(),
				VectorMetadata.KNOWLEDGE_ID, knowledgeId,
				VectorMetadata.EMBEDDING_MODEL, modelName);
		List<Document> documents = chunks.stream().map(chunk -> new Document(chunk, metadata)).toList();
		vectorStore.add(documents);
		log.info("向量写入: agent={}, knowledge={}#{}, chunks={}", agentId, knowledgeType, knowledgeId, chunks.size());
	}

	/**
	 * 按元数据层次前缀删除:filterOf 拼表达式 → 先按条件检索取回文档 id、再按 id 删除
	 * (SimpleVectorStore 未实现 doDelete(Filter.Expression),父类默认抛 UnsupportedOperationException,已实测;
	 * 占位 query + 阈值 0,结果完全由过滤条件决定)。
	 * 从左往右,一旦某层为空即短路——该层及其后各层全部删除(agent 为 long 必带,从类型上杜绝全量索引删除)。
	 * 由上层按语义组合:业务删条目传当前模型名(只清现役分区,历史分区快照由运维台回收);
	 * agent 级联传 (A, null, null, null) 即全部分区全删;运维回收传 (A, 模型, 类型)。
	 * 匹配集可能很大(层空 = 整棵子树):内置分批——每批删 DELETE_BATCH_SIZE 条,循环直至清空;返回删除块数;
	 * 未配嵌入模型时静默返回 0(级联删除不因向量侧不可用而堵死)
	 */
	public int deleteByDims(long agentId, String modelName, KnowledgeType knowledgeType, Long knowledgeId) {
		if (!embeddingAvailable()) {
			return 0;
		}
		String filter = filterOf(agentId, modelName, knowledgeType, knowledgeId);
		int deleted = 0;
		while (true) {
			List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
				.query(PLACEHOLDER_QUERY)
				.topK(DELETE_BATCH_SIZE)
				.similarityThreshold(0.0)
				.filterExpression(filter)
				.build());
			if (hits == null || hits.isEmpty()) {
				break;
			}
			int removed = hits.size();
			vectorStore.delete(hits.stream().map(Document::getId).toList());
			deleted += removed;
			if (removed < DELETE_BATCH_SIZE) {
				break;
			}
		}
		if (deleted > 0) {
			log.info("向量已删除: chunks={}, filter={}", deleted, filter);
		}
		return deleted;
	}

	/**
	 * 按 agent + 现役模型检索单一知识类型(knowledgeType 空 = 全部类型;多类型由调用方逐类型多次检索,
	 * 各得独立 topK)。命中块自带 metadata 与相似度,回源由调用方按 knowledge_type/knowledge_id 完成
	 */
	public List<Document> search(long agentId, String query, int topK, KnowledgeType knowledgeType) {
		return vectorStore.similaritySearch(SearchRequest.builder()
			.query(query)
			.topK(topK)
			.filterExpression(filterOf(agentId, embeddingModel.modelName(), knowledgeType, null))
			.build());
	}

	/**
	 * 盘点:按元数据层次组织 agent → 模型 → 知识类型,粒度到知识类型(利用 VectorStore 接口取全量,
	 * 不管底层什么库)。agentId 来自向量库元数据,与现存 agent 不一定匹配——失效判定由调用方处理
	 */
	public List<VectorPresence> vectorOverview() {
		List<Document> all = vectorStore.similaritySearch(SearchRequest.builder()
			.query(PLACEHOLDER_QUERY)
			.topK(PARTITION_SCAN_TOP_K)
			.similarityThreshold(0.0)
			.build());
		if (all == null) {
			return List.of();
		}
		// 现有文档 → 层次树 agent → 模型 → 知识类型(Tree 归组:去重与自然排序一并得到)
		Map<Long, Map<String, Set<String>>> tree = new TreeMap<>();
		for (Document doc : all) {
			Object agentId = doc.getMetadata().get(VectorMetadata.AGENT_ID);
			Object model = doc.getMetadata().get(VectorMetadata.EMBEDDING_MODEL);
			Object knowledgeType = doc.getMetadata().get(VectorMetadata.KNOWLEDGE_TYPE);
			if (agentId == null || model == null || knowledgeType == null) {
				continue;
			}
			tree.computeIfAbsent(((Number) agentId).longValue(), key -> new TreeMap<>())
				.computeIfAbsent(model.toString(), key -> new TreeSet<>())
				.add(knowledgeType.toString());
		}
		return tree.entrySet().stream()
			.map(agent -> new VectorPresence(agent.getKey(), agent.getValue().entrySet().stream()
				.map(model -> new VectorPresence.ModelPresence(model.getKey(), List.copyOf(model.getValue())))
				.toList()))
			.toList();
	}

	/**
	 * 嵌入模型是否可用:未配置时跳过向量清理——删除要先占位检索,这一查仍要嵌入;内存实现下没有模型
	 * 就不可能入过向量,跳过等价于空删,不跳过则"删文档/术语/绑表/智能体"会被堵死
	 * (异常收窄在委托 available() 内,此处只留"跳过"策略与日志)
	 */
	private boolean embeddingAvailable() {
		if (embeddingModel.available()) {
			return true;
		}
		log.warn("未配置 EMBEDDING 模型,跳过向量清理(内存向量库此时必为空)");
		return false;
	}

	/**
	 * 按元数据层次(agent → 模型 → 知识类型 → 行主键)逐层拼接过滤表达式;agent 为必带根层。
	 * 简单短路:一旦某层为空即停止——该层及其后各层不再参与过滤(删除语义 = 该层及以下全删)
	 */
	private String filterOf(long agentId, String modelName, KnowledgeType knowledgeType, Long knowledgeId) {
		StringBuilder filter = new StringBuilder(VectorMetadata.AGENT_ID + " == " + agentId);
		if (!StringUtils.hasText(modelName)) {
			return filter.toString();
		}
		filter.append(" && ").append(VectorMetadata.EMBEDDING_MODEL).append(" == '").append(modelName).append("'");
		if (knowledgeType == null) {
			return filter.toString();
		}
		filter.append(" && ").append(VectorMetadata.KNOWLEDGE_TYPE).append(" == '").append(knowledgeType.name())
			.append("'");
		if (knowledgeId != null) {
			filter.append(" && ").append(VectorMetadata.KNOWLEDGE_ID).append(" == ").append(knowledgeId);
		}
		return filter.toString();
	}

}
