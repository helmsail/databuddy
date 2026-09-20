package com.helmsail.databuddy.graph.sql;

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
import com.helmsail.databuddy.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.plan.PlanStep;
import com.helmsail.databuddy.graph.plan.PlanUtils;
import com.helmsail.databuddy.graph.util.NodeUtils;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 语义一致性节点(SQL 组质检):执行前审 SQL 文本——拦的是"能跑但答错题"的错
 * (月份差一位、SUM/AVG 用反、漏 WHERE 等,数据库全部照单全收,只有执行前的文本审计拦得住)。
 * 不过 → 原因写入 SQL_REPAIR_REASON 打回生成;调用或解析失败 → 按通过放行(质检不阻塞,
 * 语法错仍由执行环节兜底);阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class SemanticConsistencyNode implements AsyncNodeAction {

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final AgentService agentService;

	private final ObjectMapper objectMapper;

	public SemanticConsistencyNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			AgentService agentService, ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.agentService = agentService;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.semanticConsistency", contextualName = "语义一致性")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String sql = state.value(GraphKeys.SQL_QUERY, String.class).orElse("");
		if (!StringUtils.hasText(sql)) {
			log.warn("语义一致性收到空 SQL,打回生成");
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SEMANTIC_PASSED, false, GraphKeys.SEMANTIC_REASON,
					"SQL 为空", GraphKeys.SQL_REPAIR_REASON, "SQL 为空", GraphKeys.NODE_STATUS, "语义一致性未通过:SQL 为空"));
		}
		long agentId = state.value(GraphKeys.AGENT_ID, Long.class).orElse(0L);
		AgentService.DatabaseTarget target = agentService.databaseTargetOf(agentId,
				NodeUtils.stringList(state, GraphKeys.RECALLED_TABLES));
		String dialect = target == null ? "MySQL" : target.dialect();
		String canonical = state.value(GraphKeys.CANONICAL_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.SEMANTIC_CONSISTENCY,
				Map.of("dialect", dialect, "instruction", currentInstruction(state), "sql", sql, "schema",
						state.value(GraphKeys.SCHEMA, String.class).orElse("无"), "knowledge",
						state.value(GraphKeys.KNOWLEDGE, String.class).orElse("无"), "canonical_query", canonical));
		return CompletableFuture.completedFuture(assess(user));
	}

	/** 调用与解析;任何运行期失败降级为通过(质检不阻塞) */
	private Map<String, Object> assess(String user) {
		try {
			String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
			JsonNode root = NodeUtils.parseJson(objectMapper, output);
			boolean passed = root.path("passed").asBoolean(false);
			String reason = root.path("reason").asText("");
			log.info("语义一致性: passed={}, reason={}", passed, reason);
			if (passed) {
				return Map.of(GraphKeys.SEMANTIC_PASSED, true, GraphKeys.SEMANTIC_REASON, "", GraphKeys.NODE_STATUS,
						"语义一致性校验通过");
			}
			return Map.of(GraphKeys.SEMANTIC_PASSED, false, GraphKeys.SEMANTIC_REASON, reason, GraphKeys.SQL_REPAIR_REASON,
					"语义一致性未通过: " + reason, GraphKeys.NODE_STATUS, "语义一致性校验未通过:重新生成 SQL");
		}
		catch (RuntimeException e) {
			log.warn("语义一致性校验调用或解析失败,按通过放行: {}", e.getMessage());
			return Map.of(GraphKeys.SEMANTIC_PASSED, true, GraphKeys.SEMANTIC_REASON, "", GraphKeys.NODE_STATUS,
					"语义一致性校验回退:未能判定,按通过继续");
		}
	}

	/** 读计划当前步指令(防御性解析;解析不了按"无",不挡质检) */
	private String currentInstruction(OverAllState state) {
		try {
			String planJson = state.value(GraphKeys.PLAN_JSON, String.class).orElse("");
			int step = state.value(GraphKeys.PLAN_STEP, 1);
			PlanStep current = PlanUtils.stepAt(PlanUtils.parse(objectMapper, planJson), step);
			return StringUtils.hasText(current.getInstruction()) ? current.getInstruction() : "无";
		}
		catch (RuntimeException e) {
			return "无";
		}
	}

}
