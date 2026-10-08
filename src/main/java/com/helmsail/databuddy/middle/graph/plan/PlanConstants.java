package com.helmsail.databuddy.middle.graph.plan;

/**
 * 计划域常量(唯一来源):节点 ID 与各重试上限
 */
public final class PlanConstants {

	/** 规划节点(数据链第六节点):产出执行计划 */
	public static final String PLANNER = "planner";

	/** 计划执行节点(枢纽):按当前步派活,走完转报告 */
	public static final String PLAN_EXECUTOR = "plan-executor";

	/** 结构重试上限(规划节点生成→校验内部循环:坏计划原地重写;超限终止语收场) */
	public static final int STRUCTURE_RETRY_MAX = 3;

	/** 计划重试上限(人工否决 / 执行组超限升级 各打回源共享;超过 → 终止语收场) */
	public static final int PLAN_RETRY_MAX = 3;

	/** 计划步数上限(硬边界:防计划无限膨胀;仅 PlanUtils 校验使用) */
	public static final int PLAN_STEPS_MAX = 6;

	private PlanConstants() {
	}

}
