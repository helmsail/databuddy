package com.helmsail.databuddy.graph.sql;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
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
 * SQL 生成节点(SQL 组头):按计划当前步的指令生成一句 SQL,写 SQL_QUERY。
 * 打回原因(SQL_REPAIR_REASON:语义不过 / 执行失败)与上次 SQL 注入提示词重写——"带原文改"是螺旋不是打转。
 * 尝试计数每次生成 +1;超限走升级阶梯:全局重规划 ≤ MAX_PLAN_REPAIR 次,再超限终止语收场。
 * 去向写 SQL_NEXT,由分流器读;阻塞的 LLM 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class SqlGenerateNode implements AsyncNodeAction {

	/** SQL 组尝试上限(生成即计数;超限升级重规划) */
	private static final int MAX_SQL_ATTEMPT = 3;

	/** 升级超限终止语(用户可见) */
	private static final String TERMINATION = "SQL 多次生成或执行失败,本轮分析无法完成。建议换个角度描述问题后重试。";

	private final NodePromptTemplateMapper promptMapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final AgentService agentService;

	private final ObjectMapper objectMapper;

	public SqlGenerateNode(NodePromptTemplateMapper promptMapper, AiModelServiceFactory aiModelServiceFactory,
			AgentService agentService, ObjectMapper objectMapper) {
		this.promptMapper = promptMapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.agentService = agentService;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.sqlGenerate", contextualName = "SQL 生成")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		int attempt = NodeUtils.intOf(state, GraphKeys.SQL_ATTEMPT, 0) + 1;
		if (attempt > MAX_SQL_ATTEMPT) {
			return CompletableFuture.completedFuture(replan(state, "SQL 组重试超限: " + lastReason(state)));
		}
		String instruction;
		try {
			instruction = currentInstruction(state);
		}
		catch (RuntimeException e) {
			return CompletableFuture.completedFuture(replan(state, "计划解析失败: " + e.getMessage()));
		}
		long agentId = NodeUtils.longOf(state, GraphKeys.AGENT_ID);
		AgentService.DatabaseTarget target = agentService.databaseTargetOf(agentId,
				NodeUtils.stringList(state, GraphKeys.RECALLED_TABLES));
		if (target == null) {
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SQL_NEXT, "end", GraphKeys.FINAL_ANSWER,
					"无法定位分析目标库(智能体未绑定数据表,或数据表跨多个库无法判定),本轮分析无法继续。", GraphKeys.NODE_STATUS,
					"SQL 生成终止:无法定位目标库"));
		}
		String canonical = state.value(GraphKeys.CANONICAL_QUERY, String.class)
			.orElse(state.value(GraphKeys.INPUT, String.class).orElse(""));
		String schema = state.value(GraphKeys.SCHEMA, String.class).orElse("无");
		String knowledge = state.value(GraphKeys.KNOWLEDGE, String.class).orElse("无");
		String reason = state.value(GraphKeys.SQL_REPAIR_REASON, String.class).orElse("");
		String previousSql = state.value(GraphKeys.SQL_QUERY, String.class).orElse("");
		String user = NodeUtils.renderPrompt(promptMapper, GraphKeys.SQL_GENERATE,
				Map.of("dialect", target.dialect(), "schema", schema, "knowledge", knowledge, "canonical_query",
						canonical, "instruction", instruction, "retry_context", retryContext(reason, previousSql)));
		String output = aiModelServiceFactory.getChatClient().prompt().user(user).call().content();
		String sql = NodeUtils.stripFence(output).trim();
		if (!StringUtils.hasText(sql)) {
			log.warn("SQL 生成为空(第 {} 次尝试),重试", attempt);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SQL_NEXT, "regenerate", GraphKeys.SQL_ATTEMPT,
					attempt, GraphKeys.SQL_REPAIR_REASON, "生成结果为空", GraphKeys.NODE_STATUS, "SQL 生成结果为空,重试"));
		}
		log.info("SQL 生成完成(第 {} 次尝试): {}", attempt, NodeUtils.brief(sql));
		return CompletableFuture.completedFuture(Map.of(GraphKeys.SQL_QUERY, sql, GraphKeys.SQL_NEXT, "semantic",
				GraphKeys.SQL_ATTEMPT, attempt, GraphKeys.SQL_REPAIR_REASON, "", GraphKeys.NODE_STATUS,
				"SQL 生成完成(第 " + attempt + " 次尝试)"));
	}

	/** 升级重规划:计数 +1;超限终止语收场 */
	private Map<String, Object> replan(OverAllState state, String reason) {
		int count = NodeUtils.intOf(state, GraphKeys.PLAN_REPAIR_COUNT, 0) + 1;
		if (count > PlanUtils.MAX_PLAN_REPAIR) {
			log.error("SQL 组升级重规划超限,终止: {}", reason);
			return Map.of(GraphKeys.SQL_NEXT, "end", GraphKeys.FINAL_ANSWER, TERMINATION, GraphKeys.NODE_STATUS,
					"SQL 组重试超限且重规划超限:终止");
		}
		log.warn("SQL 组升级重规划(第 {} 次): {}", count, reason);
		return Map.of(GraphKeys.SQL_NEXT, "replan", GraphKeys.PLAN_REPAIR_COUNT, count, GraphKeys.PLAN_REPAIR_REASON,
				reason, GraphKeys.PLAN_STEP, 1, GraphKeys.SQL_ATTEMPT, 0, GraphKeys.NODE_STATUS, "SQL 组重试超限:升级重规划");
	}

	/** 读计划当前步指令(枢纽已校验过计划,这里防御性解析) */
	private String currentInstruction(OverAllState state) {
		String planJson = state.value(GraphKeys.PLAN_JSON, String.class).orElse("");
		int step = NodeUtils.intOf(state, GraphKeys.PLAN_STEP, 1);
		PlanStep current = PlanUtils.stepAt(PlanUtils.parse(objectMapper, planJson), step);
		return StringUtils.hasText(current.getInstruction()) ? current.getInstruction() : "无";
	}

	/** 重写上下文:首次为空;重写时给原因 + 上次 SQL(带着原文改) */
	private String retryContext(String reason, String previousSql) {
		if (!StringUtils.hasText(reason)) {
			return "(无)";
		}
		String context = reason;
		if (StringUtils.hasText(previousSql)) {
			context += "\n\n[上次生成的 SQL]\n" + previousSql;
		}
		return context;
	}

	/** 最近一次打回原因(升级路径拼进重写原因) */
	private String lastReason(OverAllState state) {
		String reason = state.value(GraphKeys.SQL_REPAIR_REASON, String.class).orElse("");
		return StringUtils.hasText(reason) ? reason : "多次尝试未成功";
	}

}
