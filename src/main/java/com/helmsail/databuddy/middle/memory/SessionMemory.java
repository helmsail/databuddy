package com.helmsail.databuddy.middle.memory;

import lombok.Data;

/**
 * 会话记忆行(session_memory 表):一行 = 一段自带标识的文本——
 * "用户: …" / "助手: …" = 对话消息;"【此前对话摘要】…" 开头 = 超窗压缩摘要(每会话至多一行);
 * 数据侧不区分行类型——生成与压缩策略全在 MemoryService
 */
@Data
public class SessionMemory {

	private Long id;

	private String sessionId;

	private String content;

}
