package com.helmsail.databuddy.session;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 会话(用户侧历史:一行 = 一次连续对话;主键即会话 UUID)。
 * 与图零耦合:图不认识会话,唯一连接点是客户端把此键同时用作图运行的会话号
 */
@Data
public class Session {

	/** 会话键(UUID,建会话时生成;主键即业务键) */
	private String id;

	/** 归属 agent */
	private Long agentId;

	/** 标题(首条消息保存时截取;可空) */
	private String title;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 最近活跃时间(存消息时 touch,列表排序用) */
	private LocalDateTime updateTime;

}
