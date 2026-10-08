package com.helmsail.databuddy.graph.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.agent.AgentService;
import com.helmsail.databuddy.agent.RetrievedChunk;
import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;
import com.helmsail.databuddy.vectorize.KnowledgeType;

import lombok.extern.slf4j.Slf4j;

/**
 * 业务知识召回节点:数据链首节点(意图识别分流的 data_analysis 去向)。
 * 先把问题结合对话历史重写成可独立理解的查询,再向量召回知识源(术语/问答/文档;
 * 表块留给后续 Schema 召回节点),格式化为带来源标注的知识文本写入 KNOWLEDGE;无命中为"无",不阻塞下游。
 * 重写调用或输出失败(异常/不可解析/为空)回退原问题检索——知识召回是增强步,不因它整轮失败;
 * 阻塞的 LLM 与检索调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class KnowledgeRecallNode implements AsyncNodeAction {

	/** 召回条数上限 */
	private static final int TOP_K = 5;

	/** 召回的知识类型(与表块分工:表块归后续 Schema 召回;逐类型依次检索,各得独立 topK) */
	private static final List<KnowledgeType> KNOWLEDGE_TYPES = List.of(KnowledgeType.TERM, KnowledgeType.QA,
			KnowledgeType.DOCUMENT);

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	private final AgentService agentService;

	public KnowledgeRecallNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper, AgentService agentService) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
		this.agentService = agentService;
	}

	@Override
	@Observed(name = "node.knowledgeRecall", contextualName = "知识召回")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String input = state.value(GraphKeys.INPUT, String.class).orElse("");
		String history = state.value(GraphKeys.SESSION_MEMORY, String.class).orElse("(无)");
		long agentId = NodeUtils.longOf(state, GraphKeys.AGENT_ID);
		String query = rewrite(input, history);
		List<RetrievedChunk> hits = new ArrayList<>();
		for (KnowledgeType type : KNOWLEDGE_TYPES) {
			hits.addAll(agentService.retrieve(agentId, query, TOP_K, type));
		}
		log.info("知识召回: agent={}, 重写查询=\"{}\", 命中 {} 条", agentId, query, hits.size());
		return CompletableFuture.completedFuture(Map.of(GraphKeys.KNOWLEDGE, format(hits), GraphKeys.PROGRESS,
				hits.isEmpty() ? "知识召回完成:未命中相关知识" : "知识召回完成:命中 " + hits.size() + " 条"));
	}

	/** 结合历史重写为独立查询;调用或输出失败(异常/不可解析/为空)回退原问题(检索仍可命中) */
	private String rewrite(String input, String history) {
		try {
			String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.KNOWLEDGE_RECALL,
					Map.of("query", input, "history", history));
			String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
			JsonNode root = NodeUtils.parseJson(objectMapper, output);
			String standalone = root.path("standalone_query").asText("");
			if (StringUtils.hasText(standalone)) {
				return standalone;
			}
			log.warn("重写结果为空,回退原问题检索: {}", NodeUtils.brief(output));
		}
		catch (RuntimeException e) {
			log.warn("重写调用或输出不可解析,回退原问题检索: {}", e.getMessage());
		}
		return input;
	}

	/** 命中块 → 带知识类型标注的知识文本(编号列出;回源字段随条目补注) */
	private String format(List<RetrievedChunk> hits) {
		if (hits.isEmpty()) {
			return "无";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hits.size(); i++) {
			RetrievedChunk hit = hits.get(i);
			sb.append(i + 1).append(". ").append(prefix(hit)).append(hit.getContent());
			Object answer = hit.getExtra().get("answer");
			if (answer != null) {
				sb.append(" A: ").append(answer);
			}
			Object name = hit.getExtra().get("name");
			if (name != null) {
				sb.append(" (文档: ").append(name).append(')');
			}
			sb.append('\n');
		}
		return sb.toString().trim();
	}

	/** 知识类型前缀 */
	private String prefix(RetrievedChunk hit) {
		return switch (hit.getKnowledgeType()) {
			case TERM -> "[术语] ";
			case QA -> "[问答] Q: ";
			case DOCUMENT -> "[文档] ";
			default -> "[" + hit.getKnowledgeType().name() + "] ";
		};
	}

}
