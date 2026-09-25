package com.helmsail.databuddy.session;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话服务:session 与 session_message 的生命周期(用户侧历史,纯 CRUD)。
 * 与图零耦合:不读写图侧任何数据;历史写入由客户端编排(存 user → 跑图 → 收尾存 assistant);
 * 删会话的图侧清理由客户端调 graph 接口完成(先停运行 → 清图记忆 → 再删本域数据)
 */
@Slf4j
@Service
public class SessionService {

	/** 标题长度上限(首条消息自动截取) */
	private static final int TITLE_MAX = 20;

	/** 缺省消息类型:纯文本(客户端不再显式传 user 消息类型) */
	private static final String DEFAULT_MESSAGE_TYPE = "text";

	private final SessionMapper sessionMapper;

	private final SessionMessageMapper messageMapper;

	public SessionService(SessionMapper sessionMapper, SessionMessageMapper messageMapper) {
		this.sessionMapper = sessionMapper;
		this.messageMapper = messageMapper;
	}

	/** 建会话:生成 UUID 主键,返回含时间戳的行 */
	public Session create(long agentId, String title) {
		Session session = new Session();
		session.setId(UUID.randomUUID().toString());
		session.setAgentId(agentId);
		session.setTitle(StringUtils.hasText(title) ? title.trim() : null);
		sessionMapper.insert(session);
		log.info("会话新建: {} (agent={})", session.getId(), agentId);
		return sessionMapper.selectById(session.getId());
	}

	/** 某 agent 的会话列表(最近活跃在前) */
	public List<Session> list(long agentId) {
		return sessionMapper.selectByAgent(agentId);
	}

	/** 会话消息(时间正序,全量) */
	public List<SessionMessage> listMessages(String sessionId) {
		return messageMapper.selectBySession(sessionId);
	}

	/** 存消息:会话必须存在;首条消息顺带填标题;返回落库后的行(含时间戳) */
	@Transactional
	public SessionMessage saveMessage(String sessionId, SessionMessage message) {
		Session session = sessionMapper.selectById(sessionId);
		if (session == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
		}
		if (message == null || message.getRole() == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "role 必填(USER / ASSISTANT)");
		}
		if (!StringUtils.hasText(message.getContent())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "content 必填");
		}
		message.setId(null);
		message.setSessionId(sessionId);
		if (!StringUtils.hasText(message.getMessageType())) {
			message.setMessageType(DEFAULT_MESSAGE_TYPE);
		}
		messageMapper.insert(message);
		if (!StringUtils.hasText(session.getTitle())) {
			sessionMapper.updateTitle(sessionId, titleOf(message.getContent())); // 首条消息填标题(顺带刷新活跃时间)
		}
		else {
			sessionMapper.touch(sessionId);
		}
		return messageMapper.selectById(message.getId());
	}

	/** 硬删会话(级联):先清消息行,再删会话行;不存在幂等成功 */
	@Transactional
	public void delete(String sessionId) {
		deleteOne(sessionId);
		log.info("会话删除(级联): {}", sessionId);
	}

	/** 删某 agent 全部会话(级联;agent 级联删除用) */
	@Transactional
	public void deleteByAgent(long agentId) {
		for (Session session : sessionMapper.selectByAgent(agentId)) {
			deleteOne(session.getId());
		}
	}

	/** 单会话清库:消息 → 会话行 */
	private void deleteOne(String sessionId) {
		messageMapper.deleteBySession(sessionId);
		sessionMapper.deleteById(sessionId);
	}

	/** 标题 = 首条消息压平空白后截前 TITLE_MAX 字 */
	private static String titleOf(String content) {
		String text = content.replaceAll("\\s+", " ").trim();
		return text.length() <= TITLE_MAX ? text : text.substring(0, TITLE_MAX);
	}

}
