package com.helmsail.databuddy.graph.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmsail.databuddy.prompt.NodePromptTemplate;
import com.helmsail.databuddy.prompt.NodePromptTemplateMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 节点公共随身工具:提示词装载渲染(renderPrompt)、模型输出 JSON 解析(parseJson)、
 * 表块名解析(parseTableName,表块内容首行约定)
 */
@Slf4j
public final class NodeUtils {

	/** 模型可能把 JSON 包进 ```json 围栏,解析前先剥掉 */
	private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");

	/** 表块内容首行约定:"表: 表名(注释)"——与 AgentBizTableService#buildContent 对齐 */
	private static final Pattern TABLE_HEAD = Pattern.compile("^\\s*表:\\s*([^\\s(（]+)");

	private NodeUtils() {
	}

	/** 取生效提示词,把 {占位符} 逐个替换;提示词不存在直接抛错(部署缺失,不兜底) */
	public static String renderPrompt(NodePromptTemplateMapper mapper, String name, Map<String, String> vars) {
		NodePromptTemplate template = mapper.selectEffective(name);
		if (template == null) {
			throw new IllegalStateException("提示词不存在: " + name);
		}
		String content = template.getContent();
		for (Map.Entry<String, String> var : vars.entrySet()) {
			content = content.replace("{" + var.getKey() + "}", var.getValue());
		}
		return content;
	}

	/** 剥围栏 → 解析 JSON;失败带输出摘要抛错(错误帧可见,便于调提示词) */
	public static JsonNode parseJson(ObjectMapper objectMapper, String raw) {
		JsonNode root = null;
		try {
			root = objectMapper.readTree(stripFence(raw));
		}
		catch (Exception e) {
			log.warn("LLM 输出解析失败: {}", brief(raw), e);
			throw new IllegalStateException("LLM 输出无法解析: " + brief(raw));
		}
		if (root == null || root.isMissingNode()) {
			throw new IllegalStateException("LLM 输出不是有效 JSON: " + brief(raw));
		}
		return root;
	}

	/** 错误信息里的输出摘要(截 200 字符) */
	public static String brief(String text) {
		String trimmed = text == null ? "" : text.trim();
		return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
	}

	/** 从表块内容解析表名(首行约定;解析不到返回 null) */
	public static String parseTableName(String content) {
		Matcher matcher = TABLE_HEAD.matcher(content == null ? "" : content);
		return matcher.find() ? matcher.group(1) : null;
	}

	/** 剥掉可能存在的 ```json 代码围栏 */
	private static String stripFence(String output) {
		Matcher matcher = FENCE.matcher(output == null ? "" : output);
		return matcher.find() ? matcher.group(1) : (output == null ? "" : output.trim());
	}

}
