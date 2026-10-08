package com.helmsail.databuddy.bottom.vectorize.splitter.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.bottom.vectorize.splitter.DocumentSplitter;
import com.helmsail.databuddy.bottom.vectorize.splitter.SplitterType;

/**
 * Markdown 切分:按 # 标题层级成块,标题行保留在块首作为上下文;md 与结构化文档适用
 */
@Component
public class MarkdownSplitter implements DocumentSplitter {

	/** 章节边界:标题行前切开(零宽断言不消费标题,标题保留给其后章节;≤3 前导空格 = CommonMark 口径) */
	private static final Pattern SECTION_BREAK = Pattern.compile("(?m)(?=^ {0,3}#{1,6}[ \\t])");

	/** 单块最大字符数,超长章节按句界回退切 */
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
		for (String section : SECTION_BREAK.split(text.replace("\r", ""))) {
			String content = section.strip();
			if (!content.isEmpty()) {
				chunks.addAll(SentenceBoundaryCutter.cut(content, MAX_CHARS));
			}
		}
		return chunks;
	}

}
