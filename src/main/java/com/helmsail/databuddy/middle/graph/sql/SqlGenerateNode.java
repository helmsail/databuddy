package com.helmsail.databuddy.middle.graph.sql;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.bottom.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.middle.biztable.AgentBizTableService;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.plan.PlanConstants;
import com.helmsail.databuddy.middle.graph.plan.PlanUtils;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;
import com.helmsail.databuddy.middle.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL 生成节点(SQL 组头):按计划当前步的任务生成一句 SQL,写 SQL_QUERY。
 * 打回原因(SQL_REPAIR_REASON:分析不过 / 执行失败)与上次 SQL 注入提示词重写——"带原文改"是螺旋不是打转。
 * 重试计数每次生成 +1;超限走升级阶梯:全局重规划 ≤ PLAN_RETRY_MAX 次,再超限终止语收场。
 * 去向写 SQL_NEXT,由分流器读;阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class SqlGenerateNode implements AsyncNodeAction {

	/** 升级超限终止语(用户可见) */
	private static final String TERMINATION = "SQL 多次生成或执行失败,本轮分析无法完成。建议换个角度描述问题后重试。";

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final AgentBizTableService tableService;

	private final ObjectMapper objectMapper;

	public SqlGenerateNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			AgentBizTableService tableService, ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.tableService = tableService;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.sqlGenerate", contextualName = "SQL 生成")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		int attempt = NodeUtils.intOf(state, GraphKeys.Control.SQL_RETRY_COUNT, 0) + 1;
		if (attempt > SqlConstants.SQL_RETRY_MAX) {
			String lastReason = state.value(GraphKeys.Control.SQL_REPAIR_REASON, String.class).orElse("");
			return CompletableFuture.completedFuture(replan(state,
					"SQL 组重试超限: " + (StringUtils.hasText(lastReason) ? lastReason : "多次尝试未成功")));
		}
		String task;
		try {
			task = PlanUtils.currentTask(objectMapper, state, "无");
		}
		catch (RuntimeException e) {
			return CompletableFuture.completedFuture(replan(state, "计划解析失败: " + e.getMessage()));
		}
		String mainQuery = state.value(GraphKeys.Info.MAIN_QUERY, String.class)
			.orElse(state.value(GraphKeys.Info.INPUT, String.class).orElse(""));
		String schema = state.value(GraphKeys.Info.SCHEMA, String.class).orElse("无");
		String knowledge = state.value(GraphKeys.Info.KNOWLEDGE, String.class).orElse("无");
		long agentId = NodeUtils.longOf(state, GraphKeys.Info.AGENT_ID);
		AgentBizTableService.DatabaseTarget target = tableService.databaseTargetOf(agentId,
				NodeUtils.stringList(state, GraphKeys.Info.RECALLED_TABLES));
		if (target == null) {
			return CompletableFuture.completedFuture(Map.of(GraphKeys.Control.SQL_NEXT, "end", GraphKeys.Info.FINAL_ANSWER,
					"无法定位分析目标库(智能体未绑定数据表,或数据表跨多个库无法判定),本轮分析无法继续。", GraphKeys.Info.PROGRESS,
					"SQL 生成终止:无法定位目标库"));
		}
		String reason = state.value(GraphKeys.Control.SQL_REPAIR_REASON, String.class).orElse("");
		String previousSql = state.value(GraphKeys.Info.SQL_QUERY, String.class).orElse("");
		// 重写上下文:首次为空;重写时给原因 + 上次 SQL(带着原文改)
		String retryContext = "(无)";
		if (StringUtils.hasText(reason)) {
			retryContext = StringUtils.hasText(previousSql) ? reason + "\n\n[上次生成的 SQL]\n" + previousSql : reason;
		}
		String user = NodeUtils.renderPrompt(promptMapper, SqlConstants.SQL_GENERATE,
				Map.of("dialect", target.dialect(), "schema", schema, "knowledge", knowledge, "main_query",
						mainQuery, "task", task, "retry_context", retryContext));
		String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
		String sql = NodeUtils.stripFence(output).trim();
		if (!StringUtils.hasText(sql)) {
			log.warn("SQL 生成为空(第 {} 次尝试),重试", attempt);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.Control.SQL_NEXT, "regenerate", GraphKeys.Control.SQL_RETRY_COUNT,
					attempt, GraphKeys.Control.SQL_REPAIR_REASON, "生成结果为空", GraphKeys.Info.PROGRESS, "SQL 生成结果为空,重试"));
		}
		log.info("SQL 生成完成(第 {} 次尝试): {}", attempt, NodeUtils.brief(sql));
		return CompletableFuture.completedFuture(Map.of(GraphKeys.Info.SQL_QUERY, sql, GraphKeys.Control.SQL_NEXT, "analyze",
				GraphKeys.Control.SQL_RETRY_COUNT, attempt, GraphKeys.Control.SQL_REPAIR_REASON, "", GraphKeys.Info.PROGRESS,
				"SQL 生成完成(第 " + attempt + " 次尝试)"));
	}

	/** 升级重规划:计数 +1;超限终止语收场 */
	private Map<String, Object> replan(OverAllState state, String reason) {
		int count = NodeUtils.intOf(state, GraphKeys.Control.PLAN_RETRY_COUNT, 0) + 1;
		if (count > PlanConstants.PLAN_RETRY_MAX) {
			log.error("SQL 组升级重规划超限,终止: {}", reason);
			return Map.of(GraphKeys.Control.SQL_NEXT, "end", GraphKeys.Info.FINAL_ANSWER, TERMINATION, GraphKeys.Info.PROGRESS,
					"SQL 组重试超限且重规划超限:终止");
		}
		log.warn("SQL 组升级重规划(第 {} 次): {}", count, reason);
		return Map.of(GraphKeys.Control.SQL_NEXT, "replan", GraphKeys.Control.PLAN_RETRY_COUNT, count, GraphKeys.Control.PLAN_REPAIR_REASON,
				reason, GraphKeys.Control.PLAN_STEP_NO, 1, GraphKeys.Control.SQL_RETRY_COUNT, 0, GraphKeys.Info.PROGRESS, "SQL 组重试超限:升级重规划");
	}

}
