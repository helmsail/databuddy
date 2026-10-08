package com.helmsail.databuddy.middle.bizdocument;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文档兜底重试(定时):只遍历有未同步行(PENDING / FAILED)的 agent,逐 agent 补刷向量化;触发件随文档子域同包。
 * 扇出(对哪些 agent 触发)在本类完成,重试逻辑在 AgentBizDocumentService;首跑延迟到启动后(等模型就绪、避开启动尖峰),之后低频轮询
 */
@Component
public class AgentBizDocumentSyncJob {

	private final AgentBizDocumentService agentBizDocumentService;

	public AgentBizDocumentSyncJob(AgentBizDocumentService agentBizDocumentService) {
		this.agentBizDocumentService = agentBizDocumentService;
	}

	/** 轮询有未同步行的 agent 并逐 agent 重试(默认:启动后 2 分钟首跑,之后每 10 分钟一轮;经 databuddy.retry.* 可覆盖) */
	@Scheduled(initialDelayString = "${databuddy.retry.initial-delay-ms:120000}",
			fixedDelayString = "${databuddy.retry.interval-ms:600000}")
	public void retryUnsynced() {
		for (Long agentId : agentBizDocumentService.unsyncedAgentIds()) {
			agentBizDocumentService.retryUnsynced(agentId);
		}
	}

}
