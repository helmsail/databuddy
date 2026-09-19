package com.helmsail.databuddy.agent.bizqa;

import java.time.LocalDateTime;

import com.helmsail.databuddy.agent.EmbeddingStatus;

import lombok.Data;

/**
 * agent 业务问答(一行 = 一组问答);仅问题向量化(同步,CRUD 即触发),答案留库回源
 */
@Data
public class AgentBizQa {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 所属 agent */
	private Long agentId;

	/** 问题(唯一键之一,向量化内容) */
	private String question;

	/** 答案(不进向量;命中后回源返回) */
	private String content;

	/** 向量化状态(PENDING / SYNCED / FAILED) */
	private EmbeddingStatus embeddingStatus;

	/** 最近一次向量化失败原因(成功时清空) */
	private String errorMsg;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
