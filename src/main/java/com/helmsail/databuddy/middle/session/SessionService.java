package com.helmsail.databuddy.middle.session;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.middle.memory.MemoryService;

import lombok.extern.slf4j.Slf4j;

/**
 * 会话服务:session / session_message 的生命周期(用户侧历史,纯 CRUD)与跨图记忆删除联动。
 * 与图零耦合:不读写图侧任何数据;历史写入由客户端编排(存 user → 跑图 → 收尾存 assistant);
 * 删会话(客户端先调 /agent/clear 停图运行)连带清该会话跨图记忆:记忆 → 消息 → 会话行
 */
@Slf4j
@Service
public class SessionService {

	/** 标题长度上限(首条消息压平后截取) */
	private static final int TITLE_MAX = 20;

	/** 单页条数上限(接口防呆;limit 超出按上限) */
	private static final int PAGE_MAX = 200;

	private final SessionMapper sessionMapper;

	private final SessionMessageMapper messageMapper;

	private final MemoryService memoryService;

	public SessionService(SessionMapper sessionMapper, SessionMessageMapper messageMapper, MemoryService memoryService) {
		this.sessionMapper = sessionMapper;
		this.messageMapper = messageMapper;
		this.memoryService = memoryService;
	}

	/** 建会话:生成 UUID 主键并落库(标题由客户端按首条消息传入,压平截断);返回会话行 */
	public Session createSession(long agentId, String title) {
		Session session = new Session();
		session.setId(UUID.randomUUID().toString());
		session.setAgentId(agentId);
		session.setTitle(StringUtils.hasText(title) ? titleOf(title) : null);
		sessionMapper.insert(session);
		log.info("会话新建: {} (agent={})", session.getId(), agentId);
		return session;
	}

	/** 建消息:落库后刷新会话活跃时间(列表排序用);返回入库后的消息 */
	@Transactional
	public SessionMessage createMessage(String sessionId, SessionMessage message) {
		if (message == null || message.getRole() == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "role 必填(USER / ASSISTANT)");
		}
		if (!StringUtils.hasText(message.getContent())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "content 必填");
		}
		message.setId(null);
		message.setSessionId(sessionId);
		messageMapper.insert(message);
		sessionMapper.touch(sessionId);
		return message;
	}

	/** 硬删会话(级联):清跨图记忆 → 消息 → 会话行;不存在幂等成功 */
	@Transactional
	public void deleteSession(String sessionId) {
		deleteOne(sessionId);
		log.info("会话删除(级联): {}", sessionId);
	}

	/** 删某 agent 全部会话(级联;agent 级联删除用) */
	@Transactional
	public void deleteSessionsByAgent(long agentId) {
		for (Session session : sessionMapper.selectByAgent(agentId)) {
			deleteOne(session.getId());
		}
	}

	/** 某 agent 的会话列表(最近活跃在前) */
	public List<Session> listSessions(long agentId) {
		return sessionMapper.selectByAgent(agentId);
	}

	/** 会话消息单页(时间正序):beforeId 空 = 最新一页;返回条数不足 limit 即已到最早 */
	public List<SessionMessage> listMessages(String sessionId, Long beforeId, int limit) {
		int size = Math.min(Math.max(limit, 1), PAGE_MAX);
		List<SessionMessage> page = messageMapper.selectBySession(sessionId, beforeId, size);
		Collections.reverse(page);
		return page;
	}

	/** 单会话清库:跨图记忆 → 消息 → 会话行 */
	private void deleteOne(String sessionId) {
		memoryService.deleteBySession(sessionId);
		messageMapper.deleteBySession(sessionId);
		sessionMapper.deleteById(sessionId);
	}

	/** 标题 = 首条消息压平空白后截前 TITLE_MAX 字 */
	private static String titleOf(String content) {
		String text = content.replaceAll("\\s+", " ").trim();
		return text.length() <= TITLE_MAX ? text : text.substring(0, TITLE_MAX);
	}

}
