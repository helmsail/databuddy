package com.helmsail.databuddy.agent.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.helmsail.databuddy.agent.bizqa.AgentBizQaService;

/**
 * 问答兜底重试(定时):补刷失败(FAILED)与遗留待办(PENDING)的向量化。
 * 只做触发,业务逻辑在 AgentBizQaService;首跑延迟到启动后(等模型就绪、避开启动尖峰),之后低频轮询
 */
@Component
public class AgentBizQaSyncJob {

	private final AgentBizQaService agentBizQaService;

	public AgentBizQaSyncJob(AgentBizQaService agentBizQaService) {
		this.agentBizQaService = agentBizQaService;
	}

	/** 轮询未同步行并重试(默认:启动后 2 分钟首跑,之后每 10 分钟一轮;经 databuddy.retry.* 可覆盖) */
	@Scheduled(initialDelayString = "${databuddy.retry.initial-delay-ms:120000}",
			fixedDelayString = "${databuddy.retry.interval-ms:600000}")
	public void retryUnsynced() {
		agentBizQaService.retryUnsyncedAll();
	}

}
