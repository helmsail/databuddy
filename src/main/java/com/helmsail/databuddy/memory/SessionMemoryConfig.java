package com.helmsail.databuddy.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.helmsail.databuddy.aimodel.AiModelServiceFactory;

/**
 * 会话记忆装配:实现层全自研(SummarizingChatMemory 直接上桌,窗口裁剪与超窗压缩全在实现内)——
 * 对接的仍是官方 ChatMemory 接口(实现自研、插座留官方:上层 SessionMemoryService 零感知);
 * 存储 = session_memory 表(见 schema.sql),窗口 = 10 轮(20 条消息)
 */
@Configuration
public class SessionMemoryConfig {

	/** 记忆窗口:10 轮对话 = 20 条消息(用户/助手各一条) */
	private static final int WINDOW_MESSAGES = 20;

	@Bean
	public ChatMemory chatMemory(SessionMemoryMapper mapper, AiModelServiceFactory aiModelServiceFactory) {
		return new SummarizingChatMemory(mapper, aiModelServiceFactory, WINDOW_MESSAGES);
	}

}
