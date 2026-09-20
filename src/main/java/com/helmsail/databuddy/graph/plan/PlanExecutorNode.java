package com.helmsail.databuddy.graph.plan;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.util.NodeUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 计划执行节点(枢纽):零 LLM 的调度器。每次到达做三件事:
 * ①计划解析与结构校验(仅第一步前,即每次新计划只校一次;不过则打回规划,超限写终止语);
 * ②人工确认闸(开关开启且未确认 → 转确认节点,确认节点会把开关关掉,只拦一次);
 * ③按当前步派活(SQL / Python 生成),步数走完转报告生成固定收尾。
 * 派活目标写 PLAN_NEXT,由分流器读;步数推进不在这里(执行成功后由组内节点 +1)
 */
@Slf4j
@Component
public class PlanExecutorNode implements AsyncNodeAction {

	/** 校验重写超限终止语(用户可见) */
	private static final String TERMINATION = "计划多次生成未通过校验,本轮分析无法继续。请调整问题描述后重试。";

	private final ObjectMapper objectMapper;

	public PlanExecutorNode(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	@Observed(name = "node.planExecutor", contextualName = "计划执行")
	public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
		int step = NodeUtils.intOf(state, GraphKeys.PLAN_STEP, 1);
		String planJson = state.value(GraphKeys.PLAN_JSON, String.class).orElse("");
		Plan plan;
		try {
			plan = PlanUtils.parse(objectMapper, planJson);
		}
		catch (Exception e) {
			return CompletableFuture.completedFuture(repair(state, "计划无法解析: " + e.getMessage()));
		}
		// 结构校验只在每次新计划的第一步前做一次(后续步重复校验无意义,参考实现的 TODO 即此)
		if (step <= 1) {
			String invalid = PlanUtils.validate(plan);
			if (invalid != null) {
				return CompletableFuture.completedFuture(repair(state, invalid));
			}
		}
		// 人工确认闸:开启则先转确认节点(确认后开关被关掉,后续步不再拦)
		if (Boolean.TRUE.equals(state.value(GraphKeys.PLAN_REVIEW_ENABLED, false))) {
			return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_VALID, true, GraphKeys.PLAN_NEXT,
					GraphKeys.PLAN_REVIEW, GraphKeys.NODE_STATUS, "计划待确认:请确认后继续执行"));
		}
		int size = plan.getExecutionPlan().size();
		// 步数走完:轻档直接到终点(跳过报告,SQL 文本即结果);常规走报告固定收尾(计划里没有报告步)
		if (step > size) {
			if (Boolean.TRUE.equals(state.value(GraphKeys.NL2SQL_MODE, false))) {
				log.info("轻档模式:计划执行完成(共 {} 步),直接收束", size);
				return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_VALID, true, GraphKeys.PLAN_NEXT,
						StateGraph.END, GraphKeys.NODE_STATUS, "轻档完成:SQL 已生成并执行"));
			}
			log.info("计划执行完成: 共 {} 步,转报告生成", size);
			return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_VALID, true, GraphKeys.PLAN_NEXT,
					GraphKeys.REPORT_GENERATOR, GraphKeys.NODE_STATUS, "计划执行完成:共 " + size + " 步,开始生成报告"));
		}
		PlanStep current = PlanUtils.stepAt(plan, step);
		log.info("派活: 第 {}/{} 步 → {}", step, size, current.getToolToUse());
		return CompletableFuture.completedFuture(Map.of(GraphKeys.PLAN_VALID, true, GraphKeys.PLAN_NEXT,
				current.getToolToUse(), GraphKeys.NODE_STATUS,
				"计划执行:第 " + step + "/" + size + " 步(" + toolText(current.getToolToUse()) + ")"));
	}

	/** 校验不过:计数 +1 打回规划;超限写终止语(分流器见终止语 → 终点) */
	private Map<String, Object> repair(OverAllState state, String reason) {
		int count = NodeUtils.intOf(state, GraphKeys.PLAN_REPAIR_COUNT, 0) + 1;
		if (count > PlanUtils.MAX_PLAN_REPAIR) {
			log.error("计划重写超限({} 次),终止: {}", PlanUtils.MAX_PLAN_REPAIR, reason);
			return Map.of(GraphKeys.PLAN_VALID, false, GraphKeys.FINAL_ANSWER, TERMINATION, GraphKeys.NODE_STATUS,
					"计划校验失败且重写超限:终止");
		}
		log.warn("计划校验未通过(第 {} 次重写): {}", count, reason);
		return Map.of(GraphKeys.PLAN_VALID, false, GraphKeys.PLAN_REPAIR_REASON, reason, GraphKeys.PLAN_REPAIR_COUNT,
				count, GraphKeys.PLAN_STEP, 1, GraphKeys.NODE_STATUS, "计划校验未通过,重新规划");
	}

	/** 工具名 → 播报用中文 */
	private String toolText(String tool) {
		return GraphKeys.PYTHON_GENERATE.equals(tool) ? "Python 生成" : "SQL 生成";
	}

}
