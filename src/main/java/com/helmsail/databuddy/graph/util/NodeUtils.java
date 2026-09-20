package com.helmsail.databuddy.graph.util;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.cloud.ai.graph.OverAllState;
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

	/** 读状态里的字符串列表(缺失/类型不符返回空表;节点侧防御性取值共用) */
	public static List<String> stringList(OverAllState state, String key) {
		Object raw = state.value(key).orElse(null);
		if (raw instanceof List<?> list) {
			return list.stream().map(String::valueOf).toList();
		}
		return List.of();
	}

	/**
	 * 读状态里的 long:检查点往返后形态多变(纯数字 / 单元素或类型封装的嵌套集合 / 字符串——恢复轮已实测
	 * 存出形如 ["java.util.ArrayList",["java.lang.Long",1]] 的封装),统一在任意嵌套里找第一个可解析的数字;
	 * 找不到返回 0。恢复链路的节点一律用本方法读数字状态键
	 */
	public static long longOf(OverAllState state, String key) {
		return findLong(state.value(key).orElse(null)).orElse(0L);
	}

	/** 读状态里的 int(与 longOf 同源的形态兼容逻辑);缺失用 defaultValue */
	public static int intOf(OverAllState state, String key, int defaultValue) {
		return findLong(state.value(key).orElse(null)).map(Long::intValue).orElse(defaultValue);
	}

	/** 任意嵌套(集合 / Map / 字符串)里找第一个可解析为 long 的值 */
	private static Optional<Long> findLong(Object raw) {
		if (raw instanceof Number number) {
			return Optional.of(number.longValue());
		}
		if (raw instanceof Iterable<?> iterable) {
			for (Object item : iterable) {
				Optional<Long> found = findLong(item);
				if (found.isPresent()) {
					return found;
				}
			}
			return Optional.empty();
		}
		if (raw instanceof Map<?, ?> map) {
			for (Object item : map.values()) {
				Optional<Long> found = findLong(item);
				if (found.isPresent()) {
					return found;
				}
			}
			return Optional.empty();
		}
		if (raw instanceof String text) {
			try {
				return Optional.of(Long.parseLong(text.trim()));
			}
			catch (NumberFormatException e) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	/** 从表块内容解析表名(首行约定;解析不到返回 null) */
	public static String parseTableName(String content) {
		Matcher matcher = TABLE_HEAD.matcher(content == null ? "" : content);
		return matcher.find() ? matcher.group(1) : null;
	}

	/** 剥掉可能存在的 ```json 代码围栏(节点侧提取代码/JSON 文本共用) */
	public static String stripFence(String output) {
		Matcher matcher = FENCE.matcher(output == null ? "" : output);
		return matcher.find() ? matcher.group(1) : (output == null ? "" : output.trim());
	}

}
