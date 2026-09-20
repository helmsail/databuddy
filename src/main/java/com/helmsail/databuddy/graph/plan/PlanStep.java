package com.helmsail.databuddy.graph.plan;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * 计划中的一步:工具 + 详细指令(指令直接作为下游节点的任务描述,决定后续生成质量)
 */
@Data
public class PlanStep {

	/** 步骤序号(1 起) */
	private int step;

	/** 工具名:sql-generate / python-generate(取值与节点 ID 对齐,枢纽据此派活) */
	@JsonProperty("tool_to_use")
	private String toolToUse;

	/** 本步详细指令(下游节点的任务提示词主体) */
	private String instruction;

}
