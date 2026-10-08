package com.helmsail.databuddy.middle.graph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图执行线的登记表(线程安全):一线程一会话的并发语义收口于此——
 * 登记即原子替换(新消息顶掉旧现场)、防重登记(人工确认的原子闸)、条件摘除(仅现任才可摘,防旧轮残响误删继任)、按会话键摘除(停止 / 级联)。
 * 无自有依赖(非 Bean):由 GraphService 持有,只管"哪条执行线在任"
 */
class GraphThreadRegistry {

	private final Map<String, GraphThread> threads = new ConcurrentHashMap<>();

	/** 登记新执行线:同键旧执行线若在,原样返回交调用方作废(原子替换) */
	GraphThread register(GraphThread thread) {
		return threads.put(thread.getThreadId(), thread);
	}

	/** 登记(仅当无现任):返回在任旧执行线或 null(防重复确认的原子闸) */
	GraphThread registerIfAbsent(GraphThread thread) {
		return threads.putIfAbsent(thread.getThreadId(), thread);
	}

	/** 在任判定(快速提示用;精确防重靠 registerIfAbsent) */
	boolean contains(String threadId) {
		return threads.containsKey(threadId);
	}

	/** 摘除并返回执行线(无则 null;外部停止 / 级联清理) */
	GraphThread remove(String threadId) {
		return threads.remove(threadId);
	}

	/** 条件摘除:仅当自己仍是现任才移除(防旧轮残响误删继任的原子判断) */
	boolean removeIfCurrent(GraphThread thread) {
		return threads.remove(thread.getThreadId(), thread);
	}

}
