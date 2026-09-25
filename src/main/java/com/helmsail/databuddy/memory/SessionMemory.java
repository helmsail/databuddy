package com.helmsail.databuddy.memory;

import lombok.Data;

/**
 * 会话记忆行(session_memory 表):一行 = 一条消息;
 * messageType = USER / ASSISTANT(窗口消息)/ SUMMARY(窗口外压缩,每会话至多一行,滚动覆盖)
 */
@Data
public class SessionMemory {

	private Long id;

	private String conversationId;

	private String messageType;

	private String content;

}
