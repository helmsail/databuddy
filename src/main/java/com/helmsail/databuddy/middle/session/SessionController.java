package com.helmsail.databuddy.middle.session;

import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.result.ApiResponse;

/**
 * 会话入口(用户侧历史,纯 CRUD,不认识图)。
 * 编排在客户端:发问 = 存 user 消息 → 跑图 → 收尾存 assistant 消息;
 * 删会话 = 先 POST /agent/clear/{sessionId} 停图运行,再 DELETE /session/{sessionId} 删本域(连带清该会话跨图记忆);
 * 成功失败均为统一信封(见 ApiResponse)
 */
@RestController
@RequestMapping("/session")
@CrossOrigin(origins = "*")
public class SessionController {

	private final SessionService sessionService;

	public SessionController(SessionService sessionService) {
		this.sessionService = sessionService;
	}

	/** 建会话:返回会话行(客户端拿 UUID 发起对话/删除) */
	@PostMapping
	public ApiResponse<Session> createSession(@RequestParam("agentId") long agentId,
			@RequestParam(value = "title", required = false) String title) {
		return ApiResponse.success(sessionService.createSession(agentId, title));
	}

	/** 建消息(客户端在发问前/收尾时调用) */
	@PostMapping("/{sessionId}/messages")
	public ApiResponse<SessionMessage> createMessage(@PathVariable("sessionId") String sessionId,
			@RequestBody SessionMessage message) {
		return ApiResponse.success(sessionService.createMessage(sessionId, message));
	}

	/** 删会话(硬删:跨图记忆 + 消息 + 会话行;图侧运行由客户端先调 /agent/clear/{sessionId} 停) */
	@DeleteMapping("/{sessionId}")
	public ApiResponse<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
		sessionService.deleteSession(sessionId);
		return ApiResponse.success();
	}

	/** 某 agent 的会话列表(最近活跃在前) */
	@GetMapping
	public ApiResponse<List<Session>> listSessions(@RequestParam("agentId") long agentId) {
		return ApiResponse.success(sessionService.listSessions(agentId));
	}

	/** 会话消息单页(时间正序;beforeId 空 = 最新一页,返回不足 limit 条即已到最早) */
	@GetMapping("/{sessionId}/messages")
	public ApiResponse<List<SessionMessage>> listMessages(@PathVariable("sessionId") String sessionId,
			@RequestParam(value = "beforeId", required = false) Long beforeId,
			@RequestParam(value = "limit", defaultValue = "50") int limit) {
		return ApiResponse.success(sessionService.listMessages(sessionId, beforeId, limit));
	}

}
