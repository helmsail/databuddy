package com.helmsail.databuddy.memory;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * agent 记忆行(agent_memory 表):一行 = 一条沉淀(口径 / 规则 / 偏好)。
 * AI 工具(AgentMemoryTools)自行增删改,用户可见可改可删;不向量化——临时沉淀体系,要转正式知识由用户升级为术语
 */
@Data
public class AgentMemory {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 所属 agent */
	private Long agentId;

	/** 记忆内容(一条一个事实,简洁陈述) */
	private String content;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
