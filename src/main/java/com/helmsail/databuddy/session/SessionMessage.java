package com.helmsail.databuddy.session;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 会话消息(给人看的无损全文;写入由客户端编排,随会话硬删而清理)
 */
@Data
public class SessionMessage {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 所属会话键(以 URL 路径为准,请求体里的忽略) */
	private String sessionId;

	/** 角色:USER / ASSISTANT */
	private MessageRole role;

	/** 消息全文(user = 原始输入,assistant = 最终回复 / 错误 / 终止提示等) */
	private String content;

	/** 消息类型:text 起步(sql/result/error/timeline 等留扩展位) */
	private String messageType;

	/** 创建时间 */
	private LocalDateTime createTime;

}
