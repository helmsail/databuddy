package com.helmsail.databuddy.bottom.vectorize.splitter.impl;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.bottom.vectorize.splitter.DocumentSplitter;
import com.helmsail.databuddy.bottom.vectorize.splitter.SplitterType;

/**
 * 整段切分:输入即一块,不做任何切分;用于已组装好的短文本(业务表 / 术语 / QA)
 */
@Component
public class WholeSplitter implements DocumentSplitter {

	@Override
	public SplitterType type() {
		return SplitterType.WHOLE;
	}

	@Override
	public List<String> split(String text) {
		if (!StringUtils.hasText(text)) {
			return List.of();
		}
		return List.of(text.strip());
	}

}
