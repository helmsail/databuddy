package com.helmsail.databuddy.session;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 会话消息(写入由客户端编排:USER 存原始提问,ASSISTANT 在图跑完获得完整输出后组装;失败轮不落库;随会话硬删而清理)
 */
@Data
public class SessionMessage {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 所属会话键(以 URL 路径为准,请求体里的忽略) */
	private String sessionId;

	/** 角色:USER / ASSISTANT */
	private MessageRole role;

	/** 消息全文(USER = 原始提问;ASSISTANT = 编排好的完整输出 JSON:blocks 段级类型在载荷内 + report) */
	private String content;

	/** 创建时间 */
	private LocalDateTime createTime;

}
