package com.helmsail.databuddy.memory;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 会话记忆(session_memory 表,系统库):一行 = 一轮对话或一条压缩条目
 */
@Data
public class SessionMemory {

	private Long id;

	/** 会话号 */
	private String sessionId;

	/** 条目类型:'turn' 原文轮 / 'summary' 压缩条目 */
	private String kind;

	/** 仅原文轮:本轮提问(写入前截断) */
	private String question;

	/** 原文轮=助手回答(截断);压缩条目=压缩文本 */
	private String answer;

	/** 写入时间 */
	private LocalDateTime createTime;

}
