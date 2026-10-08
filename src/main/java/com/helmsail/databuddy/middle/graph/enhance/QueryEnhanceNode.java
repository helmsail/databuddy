package com.helmsail.databuddy.middle.graph.enhance;

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
import com.helmsail.databuddy.bottom.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.GraphNodes;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 查询增强节点:用召回的知识把业务语言翻译成数据语言——产出主查询(指代消解、相对时间换算为
 * 绝对时间、业务术语解析成数据语言)与 2-3 条备用查询,写 MAIN_QUERY / BACKUP_QUERIES 供下游使用。
 * 调用或输出失败(异常/不可解析/主查询为空)回退原问题(备用为空)——增强是质量步,不因它整轮失败;
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
		String sessionMemory = state.value(GraphKeys.SESSION_MEMORY, String.class).orElse("(无)");
		String user = NodeUtils.renderPrompt(promptMapper, GraphNodes.QUERY_ENHANCE,
				Map.of("input", input, "knowledge", knowledge, "session_memory", sessionMemory,
						"current_time", LocalDateTime.now().format(TIME_FORMAT)));
		return CompletableFuture.completedFuture(parse(input, user));
	}

	/** 调用与解析;调用或解析失败 / 主查询为空 → 回退原问题(备用为空),如实记过程状态 */
	private Map<String, Object> parse(String input, String user) {
		try {
			String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
			JsonNode root = NodeUtils.parseJson(objectMapper, output);
			String mainQuery = root.path("main_query").asText("");
			if (StringUtils.hasText(mainQuery)) {
				List<String> backupQueries = strings(root.path("backup_queries"));
				log.info("查询增强: 主查询=\"{}\", 备用查询 {} 条", mainQuery, backupQueries.size());
				return Map.of(GraphKeys.MAIN_QUERY, mainQuery, GraphKeys.BACKUP_QUERIES, backupQueries,
						GraphKeys.PROGRESS, "查询增强完成:备用查询 " + backupQueries.size() + " 条");
			}
			log.warn("查询增强未产出有效查询,回退原问题: {}", NodeUtils.brief(output));
		}
		catch (RuntimeException e) {
			log.warn("查询增强调用或输出不可解析,回退原问题: {}", e.getMessage());
		}
		return Map.of(GraphKeys.MAIN_QUERY, input, GraphKeys.BACKUP_QUERIES, List.of(), GraphKeys.PROGRESS,
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
