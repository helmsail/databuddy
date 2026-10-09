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
 * SQL 分析节点(SQL 组闸):执行前审"SQL 文本是否完成任务"——与 Python 组同构:同一个分析闸,
 * SQL 的排位在执行前(审文本)、Python 在执行后(审数据);拦的是"能跑但答错题"的错
 * (月份差一位、SUM/AVG 用反、漏 WHERE 等,数据库全部照单全收,只有执行前的文本审计拦得住)。
 * 过检 → 转执行;不过 → 原因写 SQL_REPAIR_REASON 打回生成;调用或解析失败 → 按过检放行(质检不阻塞,
 * 语法错仍由执行环节兜底);阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class SqlAnalyzeNode implements AsyncNodeAction {

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final AgentBizTableService tableService;

	private final ObjectMapper objectMapper;

	public SqlAnalyzeNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			AgentBizTableService tableService, ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.tableService = tableService;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.sqlAnalyze", contextualName = "SQL 分析")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String sqlQuery = state.value(GraphKeys.Info.SQL_QUERY, String.class).orElse("");
		if (!StringUtils.hasText(sqlQuery)) {
			log.warn("SQL 分析收到空 SQL,打回生成");
			return CompletableFuture.completedFuture(Map.of(GraphKeys.Control.SQL_PASSED, false, GraphKeys.Control.SQL_REPAIR_REASON,
					"SQL 为空", GraphKeys.Info.PROGRESS, "SQL 分析未通过:SQL 为空"));
		}
		String mainQuery = state.value(GraphKeys.Info.MAIN_QUERY, String.class)
			.orElse(state.value(GraphKeys.Info.INPUT, String.class).orElse(""));
		long agentId = NodeUtils.longOf(state, GraphKeys.Info.AGENT_ID);
		AgentBizTableService.DatabaseTarget target = tableService.databaseTargetOf(agentId,
				NodeUtils.stringList(state, GraphKeys.Info.RECALLED_TABLES));
		String dialect = target == null ? "MySQL" : target.dialect();
		String user = NodeUtils.renderPrompt(promptMapper, SqlConstants.SQL_ANALYZE,
				Map.of("dialect", dialect, "task", PlanUtils.currentTaskOrFallback(objectMapper, state, "无"),
						"sql_query", sqlQuery, "schema", state.value(GraphKeys.Info.SCHEMA, String.class).orElse("无"),
						"knowledge", state.value(GraphKeys.Info.KNOWLEDGE, String.class).orElse("无"), "main_query",
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
			log.info("SQL 分析判定: passed={}, reason={}", passed, reason);
			if (passed) {
				return Map.of(GraphKeys.Control.SQL_PASSED, true, GraphKeys.Info.PROGRESS, "SQL 分析过检");
			}
			return Map.of(GraphKeys.Control.SQL_PASSED, false, GraphKeys.Control.SQL_REPAIR_REASON, "SQL 分析未通过: " + reason,
					GraphKeys.Info.PROGRESS, "SQL 分析未通过:重新生成 SQL");
		}
		catch (RuntimeException e) {
			log.warn("SQL 分析调用或解析失败,按过检放行: {}", e.getMessage());
			return Map.of(GraphKeys.Control.SQL_PASSED, true, GraphKeys.Info.PROGRESS, "SQL 分析回退:未能判定,按通过继续");
		}
	}

}
