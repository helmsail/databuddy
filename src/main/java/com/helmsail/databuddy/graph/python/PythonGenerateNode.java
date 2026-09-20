package com.helmsail.databuddy.graph.python;

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
import com.helmsail.databuddy.graph.plan.PlanStep;
import com.helmsail.databuddy.graph.plan.PlanUtils;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Python 生成节点(Python 组头):按计划当前步的指令生成完整可运行脚本,写 PYTHON_CODE。
 * 生成侧只给"前 5 行样例"(省 token 防幻觉),全量数据在执行侧由 input.json 提供;
 * 失败重写注入上次代码与错误(带原文改);尝试计数每次生成 +1。
 * 阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class PythonGenerateNode implements AsyncNodeAction {

	/** 生成侧样例行数(与参考一致;全量数据只进执行侧 input.json) */
	private static final int SAMPLE_ROWS = 5;

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final ObjectMapper objectMapper;

	public PythonGenerateNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.pythonGenerate", contextualName = "Python 生成")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		int attempt = state.value(GraphKeys.PYTHON_ATTEMPT, 0) + 1;
		String canonical = state.value(GraphKeys.CANONICAL_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.PYTHON_GENERATE,
				Map.of("schema", state.value(GraphKeys.SCHEMA, String.class).orElse("无"), "canonical_query", canonical,
						"instruction", currentInstruction(state), "sample_input", sampleInput(state), "retry_context",
						retryContext(state)));
		String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
		String code = NodeUtils.stripFence(output).trim();
		log.info("Python 代码生成完成(第 {} 次尝试, {} 字符)", attempt, code.length());
		return CompletableFuture.completedFuture(Map.of(GraphKeys.PYTHON_CODE, code, GraphKeys.PYTHON_ATTEMPT, attempt,
				GraphKeys.PYTHON_FAIL_REASON, "", GraphKeys.NODE_STATUS, "Python 代码生成完成(第 " + attempt + " 次尝试)"));
	}

	/** 读计划当前步指令(防御性解析;解析不了按泛化指令,不挡生成) */
	private String currentInstruction(OverAllState state) {
		try {
			String planJson = state.value(GraphKeys.PLAN_JSON, String.class).orElse("");
			int step = state.value(GraphKeys.PLAN_STEP, 1);
			PlanStep current = PlanUtils.stepAt(PlanUtils.parse(objectMapper, planJson), step);
			return StringUtils.hasText(current.getInstruction()) ? current.getInstruction() : "按计划完成本步分析";
		}
		catch (RuntimeException e) {
			return "按计划完成本步分析";
		}
	}

	/** 样例输入:最近一次 SQL 结果的前 5 行(含列名与总行数);无结果给"(无)" */
	private String sampleInput(OverAllState state) {
		String sqlResult = state.value(GraphKeys.SQL_RESULT, String.class).orElse("");
		if (!StringUtils.hasText(sqlResult)) {
			return "(无)(本步没有上游 SQL 数据)";
		}
		try {
			JsonNode root = objectMapper.readTree(sqlResult);
			JsonNode rows = root.path("rows");
			int total = root.path("row_count").asInt(rows.size());
			JsonNode sample = objectMapper.createObjectNode();
			((com.fasterxml.jackson.databind.node.ObjectNode) sample).set("columns", root.path("columns"));
			((com.fasterxml.jackson.databind.node.ObjectNode) sample).set("sample_rows",
					objectMapper.createArrayNode().addAll(rowsSizeLimited(rows)));
			((com.fasterxml.jackson.databind.node.ObjectNode) sample).put("row_count", total);
			return objectMapper.writeValueAsString(sample);
		}
		catch (Exception e) {
			log.warn("SQL 结果样例提取失败,按无样例生成: {}", e.getMessage());
			return "(无)(样例提取失败)";
		}
	}

	/** 前 SAMPLE_ROWS 行(顺序保留) */
	private java.util.List<JsonNode> rowsSizeLimited(JsonNode rows) {
		java.util.List<JsonNode> sample = new java.util.ArrayList<>(SAMPLE_ROWS);
		for (int i = 0; i < rows.size() && i < SAMPLE_ROWS; i++) {
			sample.add(rows.get(i));
		}
		return sample;
	}

	/** 重写上下文:失败原因 + 上次代码(带着原文改);首次为"(无)" */
	private String retryContext(OverAllState state) {
		String reason = state.value(GraphKeys.PYTHON_FAIL_REASON, String.class).orElse("");
		if (!StringUtils.hasText(reason)) {
			return "(无)";
		}
		String context = reason;
		String lastCode = state.value(GraphKeys.PYTHON_CODE, String.class).orElse("");
		if (StringUtils.hasText(lastCode)) {
			context += "\n\n[上次生成的代码]\n```python\n" + lastCode + "\n```";
		}
		return context;
	}

}
