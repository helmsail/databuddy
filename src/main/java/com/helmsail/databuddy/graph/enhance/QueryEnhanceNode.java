package com.helmsail.databuddy.graph.enhance;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 查询增强节点:用召回的知识把业务语言翻译成数据语言——产出规范查询(指代消解、相对时间换算为
 * 绝对时间、业务术语解析成数据语言)与 2-3 条扩展问法,写 CANONICAL_QUERY / EXPANDED_QUERIES 供下游使用。
 * 输出不可解析或规范查询为空时回退原问题(扩展为空)——增强是质量步,不因它整轮失败;
 * 阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class QueryEnhanceNode implements AsyncNodeAction {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	public QueryEnhanceNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.queryEnhance", contextualName = "查询增强")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String input = state.value(GraphKeys.INPUT, String.class).orElse("");
		String knowledge = state.value(GraphKeys.KNOWLEDGE, String.class).orElse("无");
		String history = state.value(GraphKeys.HISTORY, String.class).orElse("(无)");
		String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.QUERY_ENHANCE,
				Map.of("query", input, "knowledge", knowledge, "history", history,
						"current_time", LocalDateTime.now().format(TIME_FORMAT)));
		String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
		return CompletableFuture.completedFuture(parse(input, output));
	}

	/** 解析增强结果;不可解析或规范查询为空 → 回退原问题(扩展为空),如实记过程状态 */
	private Map<String, Object> parse(String input, String output) {
		try {
			JsonNode root = NodeUtils.parseJson(objectMapper, output);
			String canonical = root.path("canonical_query").asText("");
			if (StringUtils.hasText(canonical)) {
				List<String> expanded = strings(root.path("expanded_queries"));
				log.info("查询增强: 规范查询=\"{}\", 扩展 {} 条", canonical, expanded.size());
				return Map.of(GraphKeys.CANONICAL_QUERY, canonical, GraphKeys.EXPANDED_QUERIES, expanded,
						GraphKeys.NODE_STATUS, "查询增强完成:扩展 " + expanded.size() + " 条");
			}
			log.warn("查询增强规范查询为空,回退原问题: {}", NodeUtils.brief(output));
		}
		catch (RuntimeException e) {
			log.warn("查询增强输出不可解析,回退原问题: {}", e.getMessage());
		}
		return Map.of(GraphKeys.CANONICAL_QUERY, input, GraphKeys.EXPANDED_QUERIES, List.of(), GraphKeys.NODE_STATUS,
				"查询增强回退:沿用原问题");
	}

	/** JSON 数组 → 非空字符串列表(非数组给空表) */
	private List<String> strings(JsonNode array) {
		List<String> list = new ArrayList<>();
		if (array.isArray()) {
			for (JsonNode node : array) {
				String text = node.asText("");
				if (StringUtils.hasText(text)) {
					list.add(text);
				}
			}
		}
		return list;
	}

}
