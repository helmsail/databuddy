package com.helmsail.databuddy.agent.biztable;

import java.time.LocalDateTime;

import com.helmsail.databuddy.agent.EmbeddingStatus;

import lombok.Data;

/**
 * agent 与业务表的绑定(一行 = 绑定的一张表);向量化粒度整表一块,批量整体同步
 */
@Data
public class AgentBizTable {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 所属 agent */
	private Long agentId;

	/** 业务库配置(biz_database_config.id) */
	private Long databaseConfigId;

	/** 业务表名 */
	private String tableName;

	/** 向量化状态(PENDING / SYNCED / FAILED) */
	private EmbeddingStatus embeddingStatus;

	/** 最近一次向量化失败原因(成功时清空) */
	private String errorMsg;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
