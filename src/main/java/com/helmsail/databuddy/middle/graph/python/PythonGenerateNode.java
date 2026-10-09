package com.helmsail.databuddy.middle.graph.python;

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
import com.helmsail.databuddy.middle.graph.plan.PlanConstants;
import com.helmsail.databuddy.middle.graph.plan.PlanUtils;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Python 生成节点(Python 组头):按计划当前步的任务生成完整可运行脚本,写 PYTHON_CODE。
 * 生成侧只给"前 5 行样例"(省 token 防幻觉),全量数据在执行侧由 input.json 提供;
 * 失败重写注入上次代码与错误(带原文改);尝试计数每次生成 +1;超限走升级阶梯:
 * 全局重规划 ≤ PLAN_RETRY_MAX 次,再超限终止语收场(执行失败/分析判定不一致都汇入本口裁决)。
 * 去向写 PYTHON_NEXT,由分流器读;阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class PythonGenerateNode implements AsyncNodeAction {

	/** 升级超限终止语(用户可见) */
	private static final String TERMINATION = "Python 多次生成、执行或分析失败,本轮分析无法完成。建议换个角度描述问题后重试。";

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
		int attempt = NodeUtils.intOf(state, GraphKeys.Control.PYTHON_RETRY_COUNT, 0) + 1;
		if (attempt > PythonConstants.PYTHON_RETRY_MAX) {
			String lastReason = state.value(GraphKeys.Control.PYTHON_REPAIR_REASON, String.class).orElse("");
			return CompletableFuture.completedFuture(replan(state,
					"Python 组重试超限: " + (StringUtils.hasText(lastReason) ? lastReason : "多次尝试未成功")));
		}
		String mainQuery = state.value(GraphKeys.Info.MAIN_QUERY, String.class)
			.orElse(state.value(GraphKeys.Info.INPUT, String.class).orElse(""));
		String user = NodeUtils.renderPrompt(promptMapper, PythonConstants.PYTHON_GENERATE,
				Map.of("schema", state.value(GraphKeys.Info.SCHEMA, String.class).orElse("无"), "main_query", mainQuery,
						"task", PlanUtils.currentTaskOrFallback(objectMapper, state, "按计划完成本步分析"),
						"sample_input", sampleInput(state), "retry_context", retryContext(state)));
		String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
		String code = NodeUtils.stripFence(output).trim();
		log.info("Python 代码生成完成(第 {} 次尝试, {} 字符)", attempt, code.length());
		return CompletableFuture.completedFuture(Map.of(GraphKeys.Info.PYTHON_CODE, code, GraphKeys.Control.PYTHON_RETRY_COUNT, attempt,
				GraphKeys.Control.PYTHON_REPAIR_REASON, "", GraphKeys.Control.PYTHON_NEXT, "execute", GraphKeys.Info.PROGRESS,
				"Python 代码生成完成(第 " + attempt + " 次尝试)"));
	}

	/** 升级重规划:计数 +1;超限终止语收场(执行/分析失败共用) */
	private Map<String, Object> replan(OverAllState state, String reason) {
		int count = NodeUtils.intOf(state, GraphKeys.Control.PLAN_RETRY_COUNT, 0) + 1;
		if (count > PlanConstants.PLAN_RETRY_MAX) {
			log.error("Python 组升级重规划超限,终止: {}", reason);
			return Map.of(GraphKeys.Control.PYTHON_NEXT, "end", GraphKeys.Info.FINAL_ANSWER, TERMINATION, GraphKeys.Info.PROGRESS,
					"Python 组重试超限且重规划超限:终止");
		}
		log.warn("Python 组升级重规划(第 {} 次): {}", count, reason);
		return Map.of(GraphKeys.Control.PYTHON_NEXT, "replan", GraphKeys.Control.PLAN_RETRY_COUNT, count, GraphKeys.Control.PLAN_REPAIR_REASON,
				reason, GraphKeys.Control.PLAN_STEP_NO, 1, GraphKeys.Control.PYTHON_RETRY_COUNT, 0, GraphKeys.Info.PROGRESS,
				"Python 组重试超限:升级重规划");
	}

	/** 样例输入:最近一次 SQL 结果的前 5 行(含列名与总行数);无结果给"(无)" */
	private String sampleInput(OverAllState state) {
		String sqlResult = state.value(GraphKeys.Info.SQL_RESULT, String.class).orElse("");
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
		String reason = state.value(GraphKeys.Control.PYTHON_REPAIR_REASON, String.class).orElse("");
		if (!StringUtils.hasText(reason)) {
			return "(无)";
		}
		String context = reason;
		String lastCode = state.value(GraphKeys.Info.PYTHON_CODE, String.class).orElse("");
		if (StringUtils.hasText(lastCode)) {
			context += "\n\n[上次生成的代码]\n```python\n" + lastCode + "\n```";
		}
		return context;
	}

}
