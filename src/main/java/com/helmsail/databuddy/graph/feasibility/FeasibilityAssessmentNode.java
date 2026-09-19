package com.helmsail.databuddy.graph.feasibility;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 可行性评估节点:数据链第五节点。进下游前的"材料体检"——用规范查询 + 召回材料(表结构/表关系/业务知识)
 * + 对话历史,让 LLM 判定"就凭现有材料,这个分析做得出来吗",二分类:
 * 可分析(data_analysis)→ 放行;需要澄清(need_clarification)→ 反问写 FINAL_ANSWER,经既有 END 机制收束播报。
 * 定位是乐观的体检而非硬闸门:调用或解析失败时按可分析放行(退化为"没有本节点"之行,不阻塞用户);
 * 提示词缺失属部署问题,照例直接抛错;阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class FeasibilityAssessmentNode implements AsyncNodeAction {

	/** 判定取值(提示词契约,与种子提示词一致) */
	private static final String DATA_ANALYSIS = "data_analysis";

	private static final String NEED_CLARIFICATION = "need_clarification";

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	public FeasibilityAssessmentNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.feasibilityAssessment", contextualName = "可行性评估")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String canonical = state.value(GraphKeys.CANONICAL_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String schema = state.value(GraphKeys.SCHEMA, String.class).orElse("无");
		String relations = state.value(GraphKeys.TABLE_RELATIONS, String.class).orElse("无");
		String knowledge = state.value(GraphKeys.KNOWLEDGE, String.class).orElse("无");
		String history = state.value(GraphKeys.HISTORY, String.class).orElse("(无)");
		String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.FEASIBILITY_ASSESSMENT,
				Map.of("canonical_query", canonical, "schema", schema, "relations", relations, "knowledge", knowledge,
						"history", history));
		return CompletableFuture.completedFuture(assess(user));
	}

	/** 调用与解析;任何运行期失败降级为按可分析放行(体检不阻塞),如实记过程状态 */
	private Map<String, Object> assess(String user) {
		try {
			String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
			JsonNode root = NodeUtils.parseJson(objectMapper, output);
			String type = root.path("requirement_type").asText("");
			String clarification = root.path("clarification").asText("");
			if (NEED_CLARIFICATION.equals(type) && StringUtils.hasText(clarification)) {
				log.info("可行性评估: 需要澄清,反问=\"{}\"", clarification);
				return Map.of(GraphKeys.FINAL_ANSWER, clarification, GraphKeys.NODE_STATUS, "可行性评估完成:需要澄清");
			}
			if (DATA_ANALYSIS.equals(type)) {
				log.info("可行性评估: 可分析,放行");
				return Map.of(GraphKeys.NODE_STATUS, "可行性评估完成:可行");
			}
			log.warn("可行性评估输出不符合契约,按可行放行: {}", NodeUtils.brief(output));
		}
		catch (RuntimeException e) {
			log.warn("可行性评估调用或解析失败,按可行放行: {}", e.getMessage());
		}
		return Map.of(GraphKeys.NODE_STATUS, "可行性评估回退:未能判定,按可行继续");
	}

}
