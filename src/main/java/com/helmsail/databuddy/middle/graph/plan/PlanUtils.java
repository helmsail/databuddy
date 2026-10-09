package com.helmsail.databuddy.middle.graph.plan;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.middle.graph.GraphKeys;
import com.helmsail.databuddy.middle.graph.python.PythonConstants;
import com.helmsail.databuddy.middle.graph.sql.SqlConstants;
import com.helmsail.databuddy.middle.graph.util.NodeUtils;

/**
 * 计划随身工具:解析、结构校验、取步、取当前步任务、分步结果累积。
 * 校验只判"结构可执行"(执行组/任务/步数),不判业务对错——业务对错的入口是重写循环
 */
public final class PlanUtils {

	/** 计划允许的执行组(取值=组入口节点 ID,即枢纽可派活目标;报告固定收尾,不进计划) */
	private static final Set<String> ALLOWED_GROUPS = Set.of(SqlConstants.SQL_GENERATE, PythonConstants.PYTHON_GENERATE);

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
		if (plan == null || plan.getPlanSteps() == null || plan.getPlanSteps().isEmpty()) {
			return "执行计划为空";
		}
		if (plan.getPlanSteps().size() > PlanConstants.PLAN_STEPS_MAX) {
			return "计划步骤数 " + plan.getPlanSteps().size() + " 超过上限 " + PlanConstants.PLAN_STEPS_MAX;
		}
		for (PlanStep step : plan.getPlanSteps()) {
			if (step.getSelectGroup() == null || !ALLOWED_GROUPS.contains(step.getSelectGroup())) {
				return "步骤 " + step.getStep() + " 执行组非法: " + step.getSelectGroup()
						+ "(只允许 sql-generate / python-generate)";
			}
			if (!StringUtils.hasText(step.getTask())) {
				return "步骤 " + step.getStep() + " 缺少任务";
			}
		}
		return null;
	}

	/** 取第 step 步(1 起);越界抛出(调用前应以步数判断收口) */
	public static PlanStep stepAt(Plan plan, int step) {
		return plan.getPlanSteps().get(step - 1);
	}

	/** 从状态取当前步任务:解析失败抛出、任务为空给回退语(失败策略由调用方定,如 SQL 组升级重规划) */
	public static String currentTask(ObjectMapper objectMapper, OverAllState state, String fallback) {
		String planJson = state.value(GraphKeys.Info.PLAN_JSON, String.class).orElse("");
		int step = NodeUtils.intOf(state, GraphKeys.Control.PLAN_STEP_NO, 1);
		PlanStep current = stepAt(parse(objectMapper, planJson), step);
		return StringUtils.hasText(current.getTask()) ? current.getTask() : fallback;
	}

	/** 从状态取当前步任务(宽容版):解析失败同样回退——质检/生成侧不因计划解析问题中断 */
	public static String currentTaskOrFallback(ObjectMapper objectMapper, OverAllState state, String fallback) {
		try {
			return currentTask(objectMapper, state, fallback);
		}
		catch (RuntimeException e) {
			return fallback;
		}
	}

	/** 分步结果累积(整表回写:REPLACE 键语义;缺省空表) */
	public static Map<String, String> withEntry(Map<String, String> existing, String key, String value) {
		Map<String, String> updated = new HashMap<>(existing);
		updated.put(key, value);
		return updated;
	}

}
