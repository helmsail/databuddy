package com.helmsail.databuddy.vectorize.splitter;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Token 切分:基于 Spring AI TokenTextSplitter 默认参数(约 800 token/块,块间重叠)
 */
@Component
public class TokenSplitter implements DocumentSplitter {

	private final TokenTextSplitter splitter = new TokenTextSplitter();

	@Override
	public SplitterType type() {
		return SplitterType.TOKEN;
	}

	@Override
	public List<String> split(String text) {
		if (!StringUtils.hasText(text)) {
			return List.of();
		}
		return splitter.apply(List.of(new Document(text))).stream().map(Document::getText).toList();
	}

}
