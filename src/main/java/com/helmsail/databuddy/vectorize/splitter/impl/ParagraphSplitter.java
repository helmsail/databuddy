package com.helmsail.databuddy.vectorize.splitter.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.vectorize.splitter.DocumentSplitter;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

/**
 * 段落切分:按空行成段,小段贪心合并到块上限;超长段走降级链——
 * 空行切不动(Tika 提取的 docx/pdf 段落以单换行分隔,没有空行)就按单换行降级,
 * 行仍超限则按句界回退切(见 SentenceBoundaryCutter);中文业务文档主力策略
 */
@Component
public class ParagraphSplitter implements DocumentSplitter {

	/** 单块最大字符数(自然中文实测约 1.0~1.2 token/字,1000 字约 1000~1200 token) */
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
		List<String> pieces = new ArrayList<>();
		for (String rawParagraph : BLANK_LINE.split(text)) {
			String paragraph = rawParagraph.strip();
			if (paragraph.isEmpty()) {
				continue;
			}
			collect(paragraph, pieces);
		}
		return merge(pieces);
	}

	/** 降级链:整段不超限直接用;超限则按单换行拆,行仍超限交给句界切 */
	private void collect(String paragraph, List<String> pieces) {
		if (paragraph.length() <= MAX_CHARS) {
			pieces.add(paragraph);
			return;
		}
		boolean hasLine = false;
		for (String rawLine : paragraph.split("\n")) {
			String line = rawLine.strip();
			if (line.isEmpty()) {
				continue;
			}
			hasLine = true;
			if (line.length() <= MAX_CHARS) {
				pieces.add(line);
				continue;
			}
			pieces.addAll(SentenceBoundaryCutter.cut(line, MAX_CHARS));
		}
		if (!hasLine) {
			// 防御:整段全空白(降级链理论上不出此形态)
			pieces.addAll(SentenceBoundaryCutter.cut(paragraph, MAX_CHARS));
		}
	}

	/** 小段贪心合并到块上限(块内以空行分隔拼接) */
	private List<String> merge(List<String> pieces) {
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String piece : pieces) {
			if (current.length() > 0 && current.length() + piece.length() > MAX_CHARS) {
				chunks.add(current.toString());
				current.setLength(0);
			}
			if (current.length() > 0) {
				current.append("\n\n");
			}
			current.append(piece);
		}
		if (current.length() > 0) {
			chunks.add(current.toString());
		}
		return chunks;
	}

}
