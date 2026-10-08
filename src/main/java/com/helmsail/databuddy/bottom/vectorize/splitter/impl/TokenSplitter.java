package com.helmsail.databuddy.bottom.vectorize.splitter.impl;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.bottom.vectorize.splitter.DocumentSplitter;
import com.helmsail.databuddy.bottom.vectorize.splitter.SplitterType;

/**
 * Token 切分:基于 Spring AI TokenTextSplitter 默认参数(块上限约 800 token;
 * 回退标点仅 . ? ! 与换行,中文标点不参与;切点后的残余顺延到下一块)
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
