package com.helmsail.databuddy.graph.sql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.agent.AgentService;
import com.helmsail.databuddy.bizdatabase.BizDatabaseService;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableData;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.plan.PlanUtils;
import com.helmsail.databuddy.graph.util.NodeUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * SQL 执行节点:对业务库运行只读查询(限行 1000 / 超时 30s 由 JDBC 执行器统一施加)。
 * 成功:结果写 SQL_RESULT(契约 JSON,供 Python input.json 与前端结果帧)+ STEP_RESULTS[step_N],
 * 步号 +1、尝试清零;失败:错误原文写 SQL_REPAIR_REASON 打回生成(带原文改)。
 * 阻塞的 JDBC 调用发生在图订阅线程(boundedElastic)上,不占事件循环
 */
@Slf4j
@Component
public class SqlExecuteNode implements AsyncNodeAction {

	/** 结果行数上限(与 SqlQueryExecutor.MAX_ROWS 对齐;到顶即视为截断) */
	private static final int MAX_ROWS = 1000;

	private final AgentService agentService;

	private final BizDatabaseService bizDatabaseService;

	private final ObjectMapper objectMapper;

	public SqlExecuteNode(AgentService agentService, BizDatabaseService bizDatabaseService, ObjectMapper objectMapper) {
		this.agentService = agentService;
		this.bizDatabaseService = bizDatabaseService;
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.sqlExecute", contextualName = "SQL 执行")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		String sql = state.value(GraphKeys.SQL_QUERY, String.class).orElse("");
		if (!StringUtils.hasText(sql)) {
			log.warn("SQL 执行为空,打回生成");
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SQL_NEXT, "regenerate", GraphKeys.SQL_REPAIR_REASON,
					"SQL 为空", GraphKeys.NODE_STATUS, "SQL 为空,重新生成"));
		}
		long agentId = NodeUtils.longOf(state, GraphKeys.AGENT_ID);
		AgentService.DatabaseTarget target = agentService.databaseTargetOf(agentId,
				NodeUtils.stringList(state, GraphKeys.RECALLED_TABLES));
		if (target == null) {
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SQL_NEXT, "end", GraphKeys.FINAL_ANSWER,
					"无法定位分析目标库(智能体未绑定数据表,或数据表跨多个库无法判定),本轮分析无法继续。", GraphKeys.NODE_STATUS,
					"SQL 执行终止:无法定位目标库"));
		}
		int step = NodeUtils.intOf(state, GraphKeys.PLAN_STEP, 1);
		try {
			TableData data = bizDatabaseService.executeQuery(target.configId(), sql);
			String resultJson = resultJson(step, sql, data);
			Map<String, String> results = PlanUtils.withEntry(stepResults(state), "step_" + step, resultJson);
			log.info("SQL 执行成功: 第 {} 步, {} 行", step, data.getRows().size());
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SQL_RESULT, resultJson, GraphKeys.STEP_RESULTS, results,
					GraphKeys.PLAN_STEP, step + 1, GraphKeys.SQL_ATTEMPT, 0, GraphKeys.SQL_REPAIR_REASON, "",
					GraphKeys.SQL_NEXT, "hub", GraphKeys.NODE_STATUS, "SQL 执行完成:" + data.getRows().size() + " 行结果"));
		}
		catch (RuntimeException e) {
			String reason = StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
			log.warn("SQL 执行失败,打回生成: {}", reason);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.SQL_REPAIR_REASON, "执行失败: " + reason,
					GraphKeys.SQL_NEXT, "regenerate", GraphKeys.NODE_STATUS, "SQL 执行失败,重新生成"));
		}
	}

	/** 结果契约 JSON:{step,sql,columns,rows,row_count,truncated};行值为字符串(与 Python 契约一致) */
	private String resultJson(int step, String sql, TableData data) {
		List<String> columns = data.getColumns();
		List<Map<String, String>> rows = new ArrayList<>(data.getRows().size());
		for (List<Object> row : data.getRows()) {
			Map<String, String> mapped = new LinkedHashMap<>();
			for (int i = 0; i < columns.size(); i++) {
				Object value = i < row.size() ? row.get(i) : null;
				mapped.put(columns.get(i), value == null ? null : String.valueOf(value));
			}
			rows.add(mapped);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("step", step);
		result.put("sql", sql);
		result.put("columns", columns);
		result.put("rows", rows);
		result.put("row_count", rows.size());
		result.put("truncated", rows.size() >= MAX_ROWS);
		try {
			return objectMapper.writeValueAsString(result);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("结果序列化失败: " + e.getMessage(), e);
		}
	}

	/** 分步结果累积(整表回写:REPLACE 键语义) */
	private Map<String, String> stepResults(OverAllState state) {
		Object raw = state.value(GraphKeys.STEP_RESULTS).orElse(null);
		if (raw instanceof Map<?, ?> map) {
			Map<String, String> results = new HashMap<>();
			map.forEach((key, value) -> results.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
			return results;
		}
		return Map.of();
	}

}
