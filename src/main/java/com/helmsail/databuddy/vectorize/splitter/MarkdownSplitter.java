package com.helmsail.databuddy.vectorize.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Markdown 切分:按 # 标题层级成块,标题行保留在块首作为上下文;md 与结构化文档适用
 */
@Component
public class MarkdownSplitter implements DocumentSplitter {

	/** 标题行:# ~ ###### + 空格开头 */
	private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*");

	/** 单块最大字符数,超长章节按上限硬切 */
	private static final int MAX_CHARS = 2000;

	@Override
	public SplitterType type() {
		return SplitterType.MARKDOWN;
	}

	@Override
	public List<String> split(String text) {
		if (!StringUtils.hasText(text)) {
			return List.of();
		}
		List<String> chunks = new ArrayList<>();
		StringBuilder section = new StringBuilder();
		for (String line : text.split("\n", -1)) {
			if (HEADING.matcher(line.strip()).matches()) {
				flush(chunks, section);
			}
			section.append(line).append('\n');
		}
		flush(chunks, section);
		return chunks;
	}

	/** 章节落块:去首尾空白;超长章节按上限硬切 */
	private void flush(List<String> chunks, StringBuilder section) {
		String content = section.toString().strip();
		section.setLength(0);
		if (content.isEmpty()) {
			return;
		}
		for (int i = 0; i < content.length(); i += MAX_CHARS) {
			chunks.add(content.substring(i, Math.min(i + MAX_CHARS, content.length())));
		}
	}

}
