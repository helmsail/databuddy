package com.helmsail.databuddy.agent.bizterm;

import java.time.LocalDateTime;

import com.helmsail.databuddy.agent.EmbeddingStatus;

import lombok.Data;

/**
 * agent 业务术语(一行 = 一个术语);术语 + 释义向量化,单条同步(CRUD 即触发)
 */
@Data
public class AgentBizTerm {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 所属 agent */
	private Long agentId;

	/** 术语 */
	private String businessTerm;

	/** 同义词/别名(英文逗号分隔,可空) */
	private String synonyms;

	/** 释义(可空) */
	private String description;

	/** 向量化状态(PENDING / SYNCED / FAILED) */
	private EmbeddingStatus embeddingStatus;

	/** 最近一次向量化失败原因(成功时清空) */
	private String errorMsg;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
