package com.helmsail.databuddy.middle.graph.review;

/**
 * 确认域常量(唯一来源:注册节点、边的目标、路由值三用一源)
 */
public final class ReviewConstants {

	/** 人工确认闸:计划执行前的唯一拦截点(interruptBefore 静态中断;入口开关默认关) */
	public static final String PLAN_REVIEW = "plan-review";

	private ReviewConstants() {
	}

}
