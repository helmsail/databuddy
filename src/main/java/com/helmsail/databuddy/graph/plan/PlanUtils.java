package com.helmsail.databuddy.graph.plan;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.util.NodeUtils;

/**
 * 计划随身工具:解析、结构校验、取步、分步结果累积。
 * 校验只判"结构可执行"(工具名/指令/步数),不判业务对错——业务对错的入口是重写循环
 */
public final class PlanUtils {

	/** 计划步数上限(硬边界:防计划无限膨胀) */
	public static final int MAX_STEPS = 6;

	/** 计划重写次数上限(校验失败 / 人工否决 / 执行组升级 共用;超过 → 终止语收场) */
	public static final int MAX_PLAN_REPAIR = 3;

	/** 计划允许的工具(= 枢纽可派活目标;报告固定收尾,不进计划) */
	private static final Set<String> TOOLS = Set.of(GraphKeys.SQL_GENERATE, GraphKeys.PYTHON_GENERATE);

	private PlanUtils() {
	}

	/** 解析计划 JSON(剥围栏);失败抛 IllegalStateException,由调用方决定重写 */
	public static Plan parse(ObjectMapper objectMapper, String json) {
		try {
			Plan plan = objectMapper.readValue(NodeUtils.stripFence(json), Plan.class);
			if (plan == null) {
				throw new IllegalStateException("计划解析为空");
			}
			return plan;
		}
		catch (IllegalStateException e) {
			throw e;
		}
		catch (Exception e) {
			throw new IllegalStateException("计划解析失败: " + e.getMessage(), e);
		}
	}

	/** 结构校验:返回 null = 通过;否则为原因(写入重写提示词) */
	public static String validate(Plan plan) {
		if (plan == null || plan.getExecutionPlan() == null || plan.getExecutionPlan().isEmpty()) {
			return "执行计划为空";
		}
		if (plan.getExecutionPlan().size() > MAX_STEPS) {
			return "计划步骤数 " + plan.getExecutionPlan().size() + " 超过上限 " + MAX_STEPS;
		}
		for (PlanStep step : plan.getExecutionPlan()) {
			if (step.getToolToUse() == null || !TOOLS.contains(step.getToolToUse())) {
				return "步骤 " + step.getStep() + " 工具名非法: " + step.getToolToUse()
						+ "(只允许 sql-generate / python-generate)";
			}
			if (!StringUtils.hasText(step.getInstruction())) {
				return "步骤 " + step.getStep() + " 缺少指令";
			}
		}
		return null;
	}

	/** 取第 step 步(1 起);越界抛出(调用前应以步数判断收口) */
	public static PlanStep stepAt(Plan plan, int step) {
		return plan.getExecutionPlan().get(step - 1);
	}

	/** 分步结果累积(整表回写:REPLACE 键语义;缺省空表) */
	public static Map<String, String> withEntry(Map<String, String> existing, String key, String value) {
		Map<String, String> updated = new HashMap<>(existing);
		updated.put(key, value);
		return updated;
	}

}
