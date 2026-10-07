package com.helmsail.databuddy.agent.biztable;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * agent 与业务表的绑定(一行 = 绑定的一张表)。无向量状态字段——业务库结构会漂移,"曾同步成功"不代表新鲜,
 * 向量由人工刷新入口在使用前实时查库重刷
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

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
