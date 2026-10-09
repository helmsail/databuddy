package com.helmsail.databuddy.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.agent.AgentService;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.middle.graph.GraphSseChunk;

import lombok.extern.slf4j.Slf4j;

/**
 * MCP 工具服务(入口二):把图能力暴露给 MCP 客户端(Claude/Cursor 等)。
 * 两个工具:list_agents(选 agentId)/ nl2sql(回 SQL 文本);
 * 底层共用 AgentService.run(唯一执行入口:nl2sqlMode 参数决定图内走法,本层阻塞收帧并解释);业务错误按"工具结果文本"返回
 * 而不是协议异常(主流约定:让调用方 LLM 能读到原因并转述)。
 * 线程模型(实证):MCP 工具为同步契约,SDK 传输层(WebFlux 0.17.0)不做调度器卸载,工具在请求线程上同步执行;
 * 图执行已由图域调度到弹性线程,但等待发生在调用线程——单机单用户可接受;
 * 若未来并发调用,MCP 侧需换异步工具规格
 */
@Slf4j
@Service
public class McpServerService {

	private final AgentService agentService;

	private final ObjectMapper objectMapper;

	public McpServerService(AgentService agentService, ObjectMapper objectMapper) {
		this.agentService = agentService;
		this.objectMapper = objectMapper;
	}

	/** nl2sql 入参 */
	public record Nl2SqlRequest(@JsonPropertyDescription("自然语言查询描述,例如:查询销售额最高的10个产品") String naturalQuery,
			@JsonPropertyDescription("智能体 ID(数字字符串);可先调用 list_agents 工具获取") String agentId) {
	}

	@Tool(name = "list_agents", description = "查询可用智能体列表(含 ID、名称与描述)。调用 nl2sql 前可先用本工具确定 agentId。")
	public String listAgents() {
		return guard(() -> {
			List<Map<String, Object>> agents = agentService.list().stream().map(agent -> {
				Map<String, Object> brief = new LinkedHashMap<>();
				brief.put("id", agent.getId());
				brief.put("name", agent.getName());
				brief.put("description", agent.getDescription() == null ? "" : agent.getDescription());
				return brief;
			}).toList();
			try {
				return objectMapper.writeValueAsString(agents);
			}
			catch (JsonProcessingException e) {
				throw new IllegalStateException("智能体列表序列化失败: " + e.getMessage(), e);
			}
		});
	}

	@Tool(name = "nl2sql", description = "将自然语言问题转换为 SQL 语句:只生成并校验 SQL 并返回 SQL 文本,不返回数据。")
	public String nl2sql(Nl2SqlRequest request) {
		return guard(() -> {
			if (request == null) {
				throw new BusinessException(ErrorCode.INVALID_INPUT, "参数缺失: 请按工具 inputSchema 传入 request 对象(naturalQuery 与 agentId)");
			}
			if (!StringUtils.hasText(request.agentId())) {
				throw new BusinessException(ErrorCode.INVALID_INPUT, "agentId 不能为空(可先调用 list_agents 获取)");
			}
			long agentId;
			try {
				agentId = Long.parseLong(request.agentId().trim());
			}
			catch (NumberFormatException e) {
				throw new BusinessException(ErrorCode.INVALID_INPUT, "agentId 必须是数字: " + request.agentId());
			}
			if (!StringUtils.hasText(request.naturalQuery())) {
				throw new BusinessException(ErrorCode.INVALID_INPUT, "naturalQuery 不能为空");
			}
			// 轻档跑图(nl2sqlMode=true):计划只排 SQL 步、跳过报告;MCP 为同步契约,阻塞收帧,error 帧转业务异常
			List<ServerSentEvent<GraphSseChunk>> frames;
			try {
				frames = agentService.run(agentId, request.naturalQuery(), null, false, true).collectList().toFuture().get();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new BusinessException(ErrorCode.SYSTEM_ERROR, "执行被中断", e);
			}
			catch (ExecutionException e) {
				throw new BusinessException(ErrorCode.SYSTEM_ERROR, "执行等待失败: " + e.getMessage(), e);
			}
			List<GraphSseChunk> chunks = frames.stream().map(ServerSentEvent::data).toList();
			String error = lastText(chunks, GraphSseChunk.ERROR);
			if (StringUtils.hasText(error)) {
				throw new BusinessException(ErrorCode.SYSTEM_ERROR, error);
			}
			String sql = lastText(chunks, GraphSseChunk.SQL);
			if (StringUtils.hasText(sql)) {
				return sql;
			}
			// 未产出 SQL:优先返回图的终止语(text 帧),否则给通用说明
			String answer = lastText(chunks, GraphSseChunk.TEXT);
			return StringUtils.hasText(answer) ? "未能完成: " + answer
					: "未能完成:未生成结果(请检查智能体是否绑定了数据表,以及模型配置是否可用)";
		});
	}

	/** 工具守卫:业务错误 → "错误: …" 文本;未知错误 → "执行失败: …"(均不抛协议异常) */
	private String guard(Supplier<String> action) {
		try {
			return action.get();
		}
		catch (BusinessException e) {
			log.warn("MCP 工具调用失败: {}", e.getMessage());
			return "错误: " + e.getMessage();
		}
		catch (RuntimeException e) {
			log.error("MCP 工具调用异常", e);
			return "执行失败: " + (StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName());
		}
	}

	/** 取某类帧的最后一个文本(error / sql / text;无则空串) */
	private static String lastText(List<GraphSseChunk> chunks, String eventType) {
		String text = "";
		for (GraphSseChunk chunk : chunks) {
			if (eventType.equals(chunk.getEventType())) {
				text = chunk.getText();
			}
		}
		return text;
	}

}
