package com.helmsail.databuddy.agent;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 智能体(agent):身份注册表实体,一行 = 一个智能体。
 * 绑定关系(模型、业务库、提示词)后续按需接入,不预埋字段
 */
@Data
public class Agent {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 名称 */
	private String name;

	/** 描述(可空) */
	private String description;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
