package com.helmsail.databuddy.vectorize.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 段落切分:按空行成段,连续小段合并到块上限,超长段按上限硬切(中文业务文档主力策略)
 */
@Component
public class ParagraphSplitter implements DocumentSplitter {

	/** 单块最大字符数(中文场景约 500~700 token) */
	private static final int MAX_CHARS = 1000;

	/** 段间分隔:一个及以上空行 */
	private static final Pattern BLANK_LINE = Pattern.compile("(\\r?\\n\\s*){2,}");

	@Override
	public SplitterType type() {
		return SplitterType.PARAGRAPH;
	}

	@Override
	public List<String> split(String text) {
		if (!StringUtils.hasText(text)) {
			return List.of();
		}
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String rawParagraph : BLANK_LINE.split(text)) {
			String paragraph = rawParagraph.strip();
			if (paragraph.isEmpty()) {
				continue;
			}
			for (String piece : hardSplit(paragraph)) {
				if (current.length() > 0 && current.length() + piece.length() > MAX_CHARS) {
					chunks.add(current.toString());
					current.setLength(0);
				}
				if (current.length() > 0) {
					current.append("\n\n");
				}
				current.append(piece);
			}
		}
		if (current.length() > 0) {
			chunks.add(current.toString());
		}
		return chunks;
	}

	/** 超长段落按块上限硬切 */
	private List<String> hardSplit(String paragraph) {
		if (paragraph.length() <= MAX_CHARS) {
			return List.of(paragraph);
		}
		List<String> pieces = new ArrayList<>();
		for (int i = 0; i < paragraph.length(); i += MAX_CHARS) {
			pieces.add(paragraph.substring(i, Math.min(i + MAX_CHARS, paragraph.length())));
		}
		return pieces;
	}

}
