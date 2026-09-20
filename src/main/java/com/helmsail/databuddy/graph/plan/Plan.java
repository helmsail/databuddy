package com.helmsail.databuddy.graph.plan;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * 执行计划(规划节点产出的提示词契约):分析思路 + 步骤列表。
 * 只含 SQL / Python 两类步骤(计划执行枢纽按步派活),报告固定收尾、不进计划
 */
@Data
public class Plan {

	@JsonProperty("thought_process")
	private String thoughtProcess;

	@JsonProperty("execution_plan")
	private List<PlanStep> executionPlan;

}
