package com.helmsail.databuddy.middle.graph.intent;

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
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.memory.AgentMemoryTools;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;

/**
 * 意图识别节点:结合对话历史判定用户输入属于 data_analysis 还是 chat。
 * chat:直接产出友好回复(写 FINAL_ANSWER,由 GraphService 整段播报并落记忆);
 * data_analysis:只写分类结果,回复留给后续数据链节点;
 * 阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Component
public class IntentRecognitionNode implements AsyncNodeAction {

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	/** 记忆工具:口径 / 规则类信息由模型按需沉淀(每轮入口都在本节点,最先看到用户纠正) */
	private final AgentMemoryTools agentMemoryTools;

	public IntentRecognitionNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper, AgentMemoryTools agentMemoryTools) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
		this.agentMemoryTools = agentMemoryTools;
	}

	@Override
	@Observed(name = "node.intentRecognition", contextualName = "意图识别")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String input = state.value(GraphKeys.Info.INPUT, String.class).orElse("");
		String sessionMemory = state.value(GraphKeys.Info.SESSION_MEMORY, String.class).orElse("(无)");
		String agentMemory = state.value(GraphKeys.Info.AGENT_MEMORY, String.class).orElse("(无)");
		String user = NodeUtils.renderPrompt(promptMapper, IntentConstants.INTENT_RECOGNITION,
				Map.of("input", input, "session_memory", sessionMemory, "agent_memory", agentMemory));
		long agentId = NodeUtils.longOf(state, GraphKeys.Info.AGENT_ID);
		String output = aiModelServiceFactory.getChatClient()
			.prompt()
			.user(user)
			.tools(agentMemoryTools)
			.toolContext(Map.of(AgentMemoryTools.AGENT_ID_KEY, agentId))
			.call()
			.content();
		return CompletableFuture.completedFuture(toUpdates(output));
	}

	/** 模型输出 → 状态更新:分类与过程播报必写;chat 且有回复时写最终回复 */
	private Map<String, Object> toUpdates(String output) {
		JsonNode root = NodeUtils.parseJson(objectMapper, output);
		String classification = root.path("classification").asText("");
		if (!IntentConstants.DATA_ANALYSIS.equals(classification) && !IntentConstants.CHAT.equals(classification)) {
			throw new IllegalStateException("意图识别输出非法(classification=" + classification + "): " + NodeUtils.brief(output));
		}
		if (IntentConstants.CHAT.equals(classification)) {
			String response = root.path("response").asText("");
			if (!StringUtils.hasText(response)) {
				throw new IllegalStateException("意图识别为 chat 但未产出回复: " + NodeUtils.brief(output));
			}
			return Map.of(GraphKeys.Control.CLASSIFICATION, classification, GraphKeys.Info.FINAL_ANSWER, response,
					GraphKeys.Info.PROGRESS, "意图识别完成:闲聊");
		}
		return Map.of(GraphKeys.Control.CLASSIFICATION, classification, GraphKeys.Info.PROGRESS, "意图识别完成:数据分析");
	}

}
