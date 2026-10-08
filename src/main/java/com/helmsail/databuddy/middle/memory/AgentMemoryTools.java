package com.helmsail.databuddy.middle.memory;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * agent 记忆工具(进图 LLM 可用):AI 自行沉淀 / 修正 / 删除临时记忆——是否沉淀由模型按提示词判断。
 * agentId 走 toolContext 注入(不暴露给模型,节点侧挂载时传入);失败返回可读文本不抛异常(模型据此调整,不打断本轮)
 */
@Component
public class AgentMemoryTools {

	/** toolContext 键:agent 作用域(节点侧挂载时写入) */
	public static final String AGENT_ID_KEY = "agentId";

	private final MemoryService memoryService;

	public AgentMemoryTools(MemoryService memoryService) {
		this.memoryService = memoryService;
	}

	/** 沉淀一条记忆(内容为单条事实) */
	@Tool(name = "save_memory", description = "保存一条智能体长期记忆:用户明确给出或纠正业务口径、规则、偏好且值得以后一直沿用时报存。每条只记一个事实,内容简洁(如:销售额=含税完成额)。")
	public String saveMemory(String content, ToolContext toolContext) {
		try {
			AgentMemory memory = memoryService.add(agentId(toolContext), content);
			return "已保存记忆 #" + memory.getId();
		}
		catch (Exception e) {
			return "保存失败: " + e.getMessage();
		}
	}

	/** 重写一条已有记忆(按清单 id) */
	@Tool(name = "update_memory", description = "重写一条已有记忆(按记忆清单中的 id):内容有误、过时或需要合并时使用;content 为修正后的完整新内容。")
	public String updateMemory(long id, String content, ToolContext toolContext) {
		try {
			memoryService.update(agentId(toolContext), id, content);
			return "已更新记忆 #" + id;
		}
		catch (Exception e) {
			return "更新失败: " + e.getMessage();
		}
	}

	/** 删除一条已有记忆(按清单 id) */
	@Tool(name = "delete_memory", description = "删除一条已有记忆(按记忆清单中的 id):确认不再需要或与他条重复时使用。")
	public String deleteMemory(long id, ToolContext toolContext) {
		try {
			memoryService.delete(agentId(toolContext), id);
			return "已删除记忆 #" + id;
		}
		catch (Exception e) {
			return "删除失败: " + e.getMessage();
		}
	}

	/** 取当前运行的 agent(节点侧 toolContext 注入;缺失按系统错误处理) */
	private long agentId(ToolContext toolContext) {
		Object value = toolContext.getContext().get(AGENT_ID_KEY);
		if (value instanceof Number number) {
			return number.longValue();
		}
		throw new BusinessException(ErrorCode.SYSTEM_ERROR, "记忆工具缺少 agentId 上下文");
	}

}
