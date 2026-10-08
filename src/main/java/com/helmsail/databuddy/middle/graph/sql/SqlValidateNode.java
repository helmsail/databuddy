package com.helmsail.databuddy.middle.graph.sql;

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
import com.helmsail.databuddy.middle.biztable.AgentBizTableService;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanUtils;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL 校验节点(SQL 组质检):执行前审 SQL 文本——拦的是"能跑但答错题"的错
 * (月份差一位、SUM/AVG 用反、漏 WHERE 等,数据库全部照单全收,只有执行前的文本审计拦得住)。
 * 不过 → 原因写入 SQL_REPAIR_REASON 打回生成;调用或解析失败 → 按通过放行(质检不阻塞,
 * 语法错仍由执行环节兜底);阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class SqlValidateNode implements AsyncNodeAction {

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final AgentBizTableService tableService;

	private final ObjectMapper objectMapper;

	public SqlValidateNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			AgentBizTableService tableService, ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.tableService = tableService;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.sqlValidate", contextualName = "SQL 校验")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String sqlQuery = state.value(GraphKeys.SQL_QUERY, String.class).orElse("");
		if (!StringUtils.hasText(sqlQuery)) {
			log.warn("SQL 校验收到空 SQL,打回生成");
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SQL_PASSED, false, GraphKeys.SQL_REPAIR_REASON,
					"SQL 为空", GraphKeys.PROGRESS, "SQL 校验未通过:SQL 为空"));
		}
		String mainQuery = state.value(GraphKeys.MAIN_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		long agentId = NodeUtils.longOf(state, GraphKeys.AGENT_ID);
		AgentBizTableService.DatabaseTarget target = tableService.databaseTargetOf(agentId,
				NodeUtils.stringList(state, GraphKeys.RECALLED_TABLES));
		String dialect = target == null ? "MySQL" : target.dialect();
		String user = NodeUtils.renderPrompt(promptMapper, SqlConstants.SQL_VALIDATE,
				Map.of("dialect", dialect, "task", PlanUtils.currentTaskOrFallback(objectMapper, state, "无"),
						"sql_query", sqlQuery, "schema", state.value(GraphKeys.SCHEMA, String.class).orElse("无"),
						"knowledge", state.value(GraphKeys.KNOWLEDGE, String.class).orElse("无"), "main_query",
						mainQuery));
		return CompletableFuture.completedFuture(assess(user));
	}

	/** 调用与解析;任何运行期失败降级为通过(质检不阻塞) */
	private Map<String, Object> assess(String user) {
		try {
			String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
			JsonNode root = NodeUtils.parseJson(objectMapper, output);
			boolean passed = root.path("passed").asBoolean(false);
			String reason = root.path("reason").asText("");
			log.info("SQL 校验: passed={}, reason={}", passed, reason);
			if (passed) {
				return Map.of(GraphKeys.SQL_PASSED, true, GraphKeys.PROGRESS, "SQL 校验通过");
			}
			return Map.of(GraphKeys.SQL_PASSED, false, GraphKeys.SQL_REPAIR_REASON, "SQL 校验未通过: " + reason,
					GraphKeys.PROGRESS, "SQL 校验未通过:重新生成 SQL");
		}
		catch (RuntimeException e) {
			log.warn("SQL 校验调用或解析失败,按通过放行: {}", e.getMessage());
			return Map.of(GraphKeys.SQL_PASSED, true, GraphKeys.PROGRESS, "SQL 校验回退:未能判定,按通过继续");
		}
	}

}
