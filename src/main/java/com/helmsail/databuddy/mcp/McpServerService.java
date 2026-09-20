package com.helmsail.databuddy.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.helmsail.databuddy.agent.Agent;
import com.helmsail.databuddy.agent.AgentService;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.graph.GraphKeys;
import com.helmsail.databuddy.graph.GraphService;

import lombok.extern.slf4j.Slf4j;

/**
 * MCP 工具服务(入口二):把图能力暴露给 MCP 客户端(Claude/Cursor 等)。
 * 三个工具:list_agents(选 agentId)/ nl2sql(回 SQL 文本)/ query_data(回数据预览);
 * 底层共用 GraphService.runLight(轻档跑图,无帧无流);业务错误按"工具结果文本"返回
 * 而不是协议异常(主流约定:让调用方 LLM 能读到原因并转述)
 */
@Slf4j
@Service
public class McpServerService {

	/** query_data 预览行数上限(MCP 客户端上下文预算;超出以 row_count/truncated 说明) */
	private static final int PREVIEW_ROWS = 50;

	private final AgentService agentService;

	private final GraphService graphService;

	private final ObjectMapper objectMapper;

	public McpServerService(AgentService agentService, GraphService graphService, ObjectMapper objectMapper) {
		this.agentService = agentService;
		this.graphService = graphService;
		this.objectMapper = objectMapper;
	}

	/** nl2sql 入参 */
	public record Nl2SqlRequest(@JsonPropertyDescription("自然语言查询描述,例如:查询销售额最高的10个产品") String naturalQuery,
			@JsonPropertyDescription("智能体 ID(数字字符串);可先调用 list_agents 工具获取") String agentId) {
	}

	/** query_data 入参 */
	public record QueryDataRequest(@JsonPropertyDescription("自然语言数据问题,例如:上个月各渠道的订单总额是多少") String naturalQuery,
			@JsonPropertyDescription("智能体 ID(数字字符串);可先调用 list_agents 工具获取") String agentId) {
	}

	@Tool(description = "查询可用智能体列表(含 ID、名称与描述)。调用 nl2sql 或 query_data 前可先用本工具确定 agentId。")
	public String listAgents() {
		return guard(() -> {
			List<Map<String, Object>> agents = agentService.list().stream().map(this::brief).toList();
			try {
				return objectMapper.writeValueAsString(agents);
			}
			catch (JsonProcessingException e) {
				throw new IllegalStateException("智能体列表序列化失败: " + e.getMessage(), e);
			}
		});
	}

	@Tool(description = "将自然语言问题转换为 SQL 语句:只生成并校验 SQL 并返回 SQL 文本,不返回数据。需要查询结果数据本身时请改用 query_data。")
	public String nl2sql(Nl2SqlRequest request) {
		return guard(() -> {
			OverAllState state = graphService.runLight(requireAgentId(request.agentId()),
					requireQuestion(request.naturalQuery()));
			String sql = state.value(GraphKeys.SQL_QUERY, String.class).orElse("");
			return StringUtils.hasText(sql) ? sql : notCompleted(state);
		});
	}

	@Tool(description = "回答数据问题:自动完成取数分析并返回查询结果数据(含 SQL、列名、前 50 行数据与总行数)。适合需要具体数值、排名、对比的问题。")
	public String queryData(QueryDataRequest request) {
		return guard(() -> {
			OverAllState state = graphService.runLight(requireAgentId(request.agentId()),
					requireQuestion(request.naturalQuery()));
			String resultJson = state.value(GraphKeys.SQL_RESULT, String.class).orElse("");
			return StringUtils.hasText(resultJson) ? preview(resultJson) : notCompleted(state);
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

	/** 结果预览:契约 JSON 收敛为 {sql,columns,rows(前 PREVIEW_ROWS 行),row_count,truncated} */
	private String preview(String resultJson) {
		try {
			JsonNode root = objectMapper.readTree(resultJson);
			JsonNode all = root.path("rows");
			ObjectNode preview = objectMapper.createObjectNode();
			preview.put("sql", root.path("sql").asText(""));
			preview.set("columns", root.path("columns"));
			ArrayNode rows = objectMapper.createArrayNode();
			for (int i = 0; i < all.size() && i < PREVIEW_ROWS; i++) {
				rows.add(all.get(i));
			}
			preview.set("rows", rows);
			preview.put("row_count", root.path("row_count").asInt(all.size()));
			preview.put("truncated", root.path("truncated").asBoolean(false) || all.size() > rows.size());
			return objectMapper.writeValueAsString(preview);
		}
		catch (Exception e) {
			log.warn("结果预览构建失败,按原样返回: {}", e.getMessage());
			return resultJson;
		}
	}

	/** 未产出目标字段:优先返回图的终止语(澄清/超限),否则给通用说明 */
	private String notCompleted(OverAllState state) {
		String answer = state.value(GraphKeys.FINAL_ANSWER, String.class).orElse("");
		return StringUtils.hasText(answer) ? "未能完成: " + answer
				: "未能完成:未生成结果(请检查智能体是否绑定了数据表,以及模型配置是否可用)";
	}

	/** 智能体摘要(工具输出精简字段) */
	private Map<String, Object> brief(Agent agent) {
		Map<String, Object> brief = new LinkedHashMap<>();
		brief.put("id", agent.getId());
		brief.put("name", agent.getName());
		brief.put("description", agent.getDescription() == null ? "" : agent.getDescription());
		return brief;
	}

	/** 参数校验:agentId 必填且为数字 */
	private long requireAgentId(String agentId) {
		if (!StringUtils.hasText(agentId)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "agentId 不能为空(可先调用 list_agents 获取)");
		}
		try {
			return Long.parseLong(agentId.trim());
		}
		catch (NumberFormatException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "agentId 必须是数字: " + agentId);
		}
	}

	/** 参数校验:问题必填 */
	private String requireQuestion(String question) {
		if (!StringUtils.hasText(question)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "question 不能为空");
		}
		return question;
	}

}
