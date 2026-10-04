package com.helmsail.databuddy.vectorize.splitter.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * 超长文本切割原语(切分器共用):按上限分片,每片优先回退到最后一个句末标点再切,
 * 找不到标点才按上限硬切——降级链的最底层,保证任何形态的文本都能落块
 */
final class SentenceBoundaryCutter {

	/** 可回退的句末边界(中英标点 + 换行) */
	private static final String SENTENCE_ENDS = "。！？；…!?;.\n";

	private SentenceBoundaryCutter() {
	}

	/** 按上限切分:每片优先以句末标点收尾,无标点则硬切(空白片自动丢弃) */
	static List<String> cut(String text, int maxChars) {
		List<String> pieces = new ArrayList<>();
		int start = 0;
		while (start < text.length()) {
			int end = Math.min(start + maxChars, text.length());
			if (end < text.length()) {
				int boundary = lastSentenceEnd(text, start, end);
				if (boundary > start) {
					end = boundary;
				}
			}
			String piece = text.substring(start, end).strip();
			if (!piece.isEmpty()) {
				pieces.add(piece);
			}
			start = end;
		}
		return pieces;
	}

	/** 在 (start, end) 开区间内找最后一个句末标点,返回其后一位;找不到返回 -1(避免切出单字符片) */
	private static int lastSentenceEnd(String text, int start, int end) {
		for (int i = end - 1; i > start; i--) {
			if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0) {
				return i + 1;
			}
		}
		return -1;
	}

}
