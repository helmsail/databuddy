package com.helmsail.databuddy.graph;

/**
 * 图的状态键常量(KeyStrategy 声明、节点读写、对外事件共用同一份定义)
 */
public final class GraphKeys {

	/** 用户原始输入 */
	public static final String INPUT = "input";

	/** 面向用户的最终回复(节点写入;事件白名单播报) */
	public static final String FINAL_ANSWER = "final_answer";

	private GraphKeys() {
	}

}
