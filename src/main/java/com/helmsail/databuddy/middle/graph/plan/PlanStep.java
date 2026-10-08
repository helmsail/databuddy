package com.helmsail.databuddy.middle.graph.plan;

import lombok.Data;

/**
 * 计划中的一步:执行组 + 本步任务(任务描述直接作为下游节点的任务输入,决定后续生成质量)
 */
@Data
public class PlanStep {

	/** 步骤序号(1 起) */
	private int step;

	/** 执行组(SQL 组 / Python 组):取值=组入口节点 ID(sql-generate / python-generate),枢纽据此派活 */
	private String selectGroup;

	/** 本步任务(下游节点的任务提示词主体) */
	private String task;

}
