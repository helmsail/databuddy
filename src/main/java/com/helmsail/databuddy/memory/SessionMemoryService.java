package com.helmsail.databuddy.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 会话记忆服务:跨轮记忆的唯一出入口,构建在官方 ChatMemory 接口之上(实现层全自研,装配见 SessionMemoryConfig)。
 * 本类只做三件事:进图前拼上文(buildContext);成功收尾追加一轮(finishTurn);删会话时清空(deleteConversation)。
 * 键用官方记忆层的词:conversationId(值即业务侧的会话键);窗口裁剪与超窗压缩全在实现层
 * (SummarizingChatMemory,落 session_memory);读写时机全部由外部显式调用(停止/出错不落库,记忆天然干净)
 */
@Component
public class SessionMemoryService {

	/** 单轮落库前的截断长度(防粘贴超长文本) */
	private static final int MAX_QUESTION_CHARS = 1000;

	private static final int MAX_ANSWER_CHARS = 500;

	private final ChatMemory chatMemory;

	public SessionMemoryService(ChatMemory chatMemory) {
		this.chatMemory = chatMemory;
	}

	/** 进图前:记忆消息拼成上文文本(摘要原文 + 逐行"用户: xx / 助手: xx");无记忆返回 "(无)" */
	public String buildContext(String conversationId) {
		List<Message> messages = chatMemory.get(conversationId);
		List<String> lines = new ArrayList<>(messages.size());
		for (Message message : messages) {
			if (message.getMessageType() == MessageType.USER) {
				lines.add("用户: " + message.getText());
			}
			else if (message.getMessageType() == MessageType.ASSISTANT) {
				lines.add("助手: " + message.getText());
			}
			else {
				lines.add(message.getText()); // 摘要:SystemMessage 首条,自带"【此前对话摘要】"前缀
			}
		}
		return lines.isEmpty() ? "(无)" : String.join("\n", lines);
	}

	/** 成功收尾(只有成功才调):输出为空整轮跳过(如 data_analysis 暂无文本);否则追加"问题+回答"一轮 */
	public void finishTurn(String conversationId, String question, String answer) {
		if (!StringUtils.hasText(answer)) {
			return;
		}
		chatMemory.add(conversationId, List.of(new UserMessage(truncate(question, MAX_QUESTION_CHARS)),
				new AssistantMessage(truncate(answer.trim(), MAX_ANSWER_CHARS))));
	}

	/** 清某会话(conversationId)的全部记忆(删会话编排中由图侧接口调用;本组件不判断时机) */
	public void deleteConversation(String conversationId) {
		chatMemory.clear(conversationId);
	}

	/** 截断;null 原样返回 */
	private static String truncate(String text, int maxChars) {
		if (text == null || text.length() <= maxChars) {
			return text;
		}
		return text.substring(0, maxChars);
	}

}
