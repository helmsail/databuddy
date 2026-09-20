package com.helmsail.databuddy.session;

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
 * 删会话 = 先 POST /graph/stop 再 DELETE /graph/memory,最后删本域(先停运行,再清两边);
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
	public ApiResponse<Session> create(@RequestParam("agentId") long agentId,
			@RequestParam(value = "title", required = false) String title) {
		return ApiResponse.success(sessionService.create(agentId, title));
	}

	/** 某 agent 的会话列表(最近活跃在前) */
	@GetMapping
	public ApiResponse<List<Session>> list(@RequestParam("agentId") long agentId) {
		return ApiResponse.success(sessionService.list(agentId));
	}

	/** 会话消息(时间正序,全量) */
	@GetMapping("/{sessionId}/messages")
	public ApiResponse<List<SessionMessage>> messages(@PathVariable("sessionId") String sessionId) {
		return ApiResponse.success(sessionService.listMessages(sessionId));
	}

	/** 存消息(客户端在发问前/收尾时调用;首条顺带填标题) */
	@PostMapping("/{sessionId}/messages")
	public ApiResponse<SessionMessage> saveMessage(@PathVariable("sessionId") String sessionId,
			@RequestBody SessionMessage message) {
		return ApiResponse.success(sessionService.saveMessage(sessionId, message));
	}

	/** 删会话(硬删:消息 + 会话行;图侧清理由客户端先调 /graph/stop 与 /graph/memory) */
	@DeleteMapping("/{sessionId}")
	public ApiResponse<Void> delete(@PathVariable("sessionId") String sessionId) {
		sessionService.delete(sessionId);
		return ApiResponse.success();
	}

}
