package com.helmsail.databuddy.middle.graph.knowledge;

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
import com.helmsail.databuddy.bottom.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.middle.bizdocument.AgentBizDocumentService;
import com.helmsail.databuddy.middle.bizqa.AgentBizQaService;
import com.helmsail.databuddy.middle.bizterm.AgentBizTermService;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;
import com.helmsail.databuddy.bottom.vectorize.RetrievedChunk;

import lombok.extern.slf4j.Slf4j;

/**
 * 业务知识召回节点:数据链首节点(意图识别分流的 data_analysis 去向)。
 * 先把问题结合对话历史重写成可独立理解的查询,再向量召回知识源(术语/问答/文档;
 * 表块留给后续 Schema 召回节点),并把智能体记忆(口径/规则/偏好)并入,汇成"业务语义总集"写入 KNOWLEDGE;
 * 两段皆空为"无",不阻塞下游。
 * 重写调用或输出失败(异常/不可解析/为空)回退原问题检索——知识召回是增强步,不因它整轮失败;
 * 阻塞的 LLM 与检索调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class KnowledgeRecallNode implements AsyncNodeAction {

	/** 召回条数上限(逐域独立检索,各得独立 topK) */
	private static final int TOP_K = 5;

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	private final AgentBizTermService bizTermService;

	private final AgentBizQaService bizQaService;

	private final AgentBizDocumentService bizDocumentService;

	public KnowledgeRecallNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper, AgentBizTermService bizTermService, AgentBizQaService bizQaService,
			AgentBizDocumentService bizDocumentService) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
		this.bizTermService = bizTermService;
		this.bizQaService = bizQaService;
		this.bizDocumentService = bizDocumentService;
	}

	@Override
	@Observed(name = "node.knowledgeRecall", contextualName = "知识召回")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String input = state.value(GraphKeys.INPUT, String.class).orElse("");
		String history = state.value(GraphKeys.SESSION_MEMORY, String.class).orElse("(无)");
		String memory = state.value(GraphKeys.AGENT_MEMORY, String.class).orElse("(无)");
		long agentId = NodeUtils.longOf(state, GraphKeys.AGENT_ID);
		String query = rewrite(input, history);
		// 逐域独立检索(术语 / 问答 / 文档各得独立 topK;表块归后续 Schema 召回)
		List<RetrievedChunk> hits = new ArrayList<>();
		hits.addAll(bizTermService.retrieve(agentId, query, TOP_K));
		hits.addAll(bizQaService.retrieve(agentId, query, TOP_K));
		hits.addAll(bizDocumentService.retrieve(agentId, query, TOP_K));
		log.info("知识召回: agent={}, 重写查询=\"{}\", 命中 {} 条", agentId, query, hits.size());
		return CompletableFuture.completedFuture(Map.of(GraphKeys.KNOWLEDGE, collect(hits, memory), GraphKeys.PROGRESS,
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

	/** 业务语义总集:有记忆先给记忆段(口径 / 规则 / 偏好,置于前优先参考),其后拼召回知识;两段皆空为"无" */
	private String collect(List<RetrievedChunk> hits, String memory) {
		String knowledge = format(hits);
		if (!StringUtils.hasText(memory) || "(无)".equals(memory)) {
			return knowledge;
		}
		String section = "【智能体记忆(此前沉淀的口径 / 规则 / 偏好)】\n" + memory;
		return "无".equals(knowledge) ? section : section + "\n\n" + knowledge;
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
