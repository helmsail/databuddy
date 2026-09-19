package com.helmsail.databuddy.vectorize.splitter;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 切分器工厂:按策略类型索引所有 DocumentSplitter 实现(由 Spring 自动注入)
 */
@Component
public class DocumentSplitterFactory {

	private final Map<SplitterType, DocumentSplitter> splitters = new EnumMap<>(SplitterType.class);

	public DocumentSplitterFactory(List<DocumentSplitter> splitterList) {
		splitterList.forEach(splitter -> splitters.put(splitter.type(), splitter));
	}

	public DocumentSplitter get(SplitterType type) {
		DocumentSplitter splitter = splitters.get(type);
		if (splitter == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的切分策略: " + type);
		}
		return splitter;
	}

}
