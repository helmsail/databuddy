package com.helmsail.databuddy.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.aimodel.AiModelServiceFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * 摘要记忆(全自研,实现官方 ChatMemory 接口):不依赖官方窗口与仓库——
 * 窗口裁剪(session_memory 表保留最近 maxMessages 条)与超窗压缩(旧摘要 + 被挤出消息 → 新摘要)
 * 全在本类;键 = conversationId(值即业务侧会话键)。压缩失败保留原行(下次溢出重试,不丢消息)
 */
@Slf4j
public class SummarizingChatMemory implements ChatMemory {

	/** 摘要行类型标记(session_memory.message_type) */
	private static final String SUMMARY = "SUMMARY";

	/** 摘要上限(提示词约束 + 落库前兜底截断) */
	private static final int MAX_SUMMARY_CHARS = 300;

	/** 压缩提示词(起步放代码;要在线调再挪进提示词表) */
	private static final String SUMMARY_PROMPT = """
			你是对话压缩助手。把"已有摘要"与"新增对话"合并压缩为一段不超过 300 字的摘要,
			保留:讨论主题、数据口径(库/表/指标/时间范围)、已达成的结论;丢弃寒暄与重复内容。
			只输出摘要文本,不要任何前缀、标题或解释。
			""";

	private final SessionMemoryMapper mapper;

	private final AiModelServiceFactory aiModelServiceFactory;

	private final int maxMessages;

	public SummarizingChatMemory(SessionMemoryMapper mapper, AiModelServiceFactory aiModelServiceFactory,
			int maxMessages) {
		this.mapper = mapper;
		this.aiModelServiceFactory = aiModelServiceFactory;
		this.maxMessages = maxMessages;
	}

	@Override
	public void add(String conversationId, List<Message> messages) {
		// 先写新消息、再按总量裁剪:超出窗口的最早若干条压缩进摘要后删除;压缩失败保留(下次溢出重试)
		for (Message message : messages) {
			SessionMemory row = new SessionMemory();
			row.setConversationId(conversationId);
			row.setMessageType(message.getMessageType().name());
			row.setContent(message.getText());
			mapper.insert(row);
		}
		List<SessionMemory> window = mapper.selectMessages(conversationId);
		int overflow = window.size() - maxMessages;
		if (overflow <= 0) {
			return;
		}
		List<SessionMemory> evicted = window.subList(0, overflow);
		if (compress(conversationId, evicted)) {
			mapper.deleteByIds(conversationId, evicted.stream().map(SessionMemory::getId).toList());
		}
	}

	@Override
	public List<Message> get(String conversationId) {
		List<Message> result = new ArrayList<>();
		String summary = mapper.selectSummary(conversationId);
		if (StringUtils.hasText(summary)) {
			result.add(new SystemMessage("【此前对话摘要】" + summary));
		}
		for (SessionMemory row : mapper.selectMessages(conversationId)) {
			result.add(switch (MessageType.valueOf(row.getMessageType())) {
				case USER -> new UserMessage(row.getContent());
				case ASSISTANT -> new AssistantMessage(row.getContent());
				default -> new SystemMessage(row.getContent());
			});
		}
		return result;
	}

	@Override
	public void clear(String conversationId) {
		mapper.deleteByConversation(conversationId);
	}

	/** 压缩:旧摘要 + 被挤出消息 → 新摘要(存在原地更新,否则首插);失败返回 false,由调用方保留原行 */
	private boolean compress(String conversationId, List<SessionMemory> evicted) {
		String oldSummary = mapper.selectSummary(conversationId);
		StringBuilder dialog = new StringBuilder();
		for (SessionMemory row : evicted) {
			dialog.append(MessageType.USER.name().equals(row.getMessageType()) ? "用户: " : "助手: ")
				.append(row.getContent())
				.append("\n");
		}
		String user = (StringUtils.hasText(oldSummary) ? "已有摘要:\n" + oldSummary + "\n\n" : "")
				+ "新增对话:\n" + dialog;
		String summaryText;
		try {
			summaryText = aiModelServiceFactory.getChatClient().prompt().user(SUMMARY_PROMPT + "\n\n" + user).call().content();
		}
		catch (Exception e) {
			log.warn("记忆压缩失败,保留原行待重试: conversationId={}", conversationId, e);
			return false;
		}
		if (!StringUtils.hasText(summaryText)) {
			log.warn("记忆压缩返回空,保留原行待重试: conversationId={}", conversationId);
			return false;
		}
		String summary = truncate(summaryText, MAX_SUMMARY_CHARS);
		if (mapper.updateSummary(conversationId, summary) == 0) {
			SessionMemory row = new SessionMemory();
			row.setConversationId(conversationId);
			row.setMessageType(SUMMARY);
			row.setContent(summary);
			mapper.insert(row);
		}
		return true;
	}

	/** 截断 */
	private static String truncate(String text, int maxChars) {
		return text.length() <= maxChars ? text : text.substring(0, maxChars);
	}

}
