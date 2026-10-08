package com.helmsail.databuddy.middle.graph.plan;

import java.util.List;

import lombok.Data;

/**
 * 执行计划(规划节点产出的提示词契约):计划标题 + 步骤列表。
 * 定位:拓扑(节点与合法走法)编译期固定,计划决定"这次怎么走"——几步、每步派哪个组、每步带什么任务;
 * 枢纽读它派活与收口,组节点读它取当前步任务,同一份数据两种消费。
 * 约束:只含 SQL / Python 两类步骤(取值与图节点 ID 对齐),报告固定收尾、不进计划;
 * 重试计数等调度参数是独立键族,不随计划携带
 */
@Data
public class Plan {

	/** 计划标题(一句话概括分析思路:已核对哪些表和字段、准备怎么做) */
	private String planTitle;

	/** 步骤列表(每步 = 执行组 + 任务) */
	private List<PlanStep> planSteps;

}
