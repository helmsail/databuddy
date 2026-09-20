package com.helmsail.databuddy.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具装配:把 McpServerService 的 @Tool 方法包成 ToolCallbackProvider bean,
 * MCP starter(webflux)自动扫描并暴露到 /mcp 端点(协议见 application.yml 的 spring.ai.mcp.server)。
 * 我们的 ChatClient 由 AiModelServiceFactory 运行期构建(非 starter 装配的 ChatModel),
 * 不存在参考里"ChatModel 初始化即扫 tool 而 tool 又依赖 LLM"的循环依赖,无需自定义注解延迟扫描
 */
@Configuration
public class McpServerConfig {

	@Bean
	public ToolCallbackProvider mcpServerTools(McpServerService mcpServerService) {
		return MethodToolCallbackProvider.builder().toolObjects(mcpServerService).build();
	}

}
