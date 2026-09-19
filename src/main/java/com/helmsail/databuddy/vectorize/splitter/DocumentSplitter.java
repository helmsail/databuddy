package com.helmsail.databuddy.vectorize.splitter;

import java.util.List;

/**
 * 文档切分器:一种策略一个实现,由 DocumentSplitterFactory 按类型派发(与方言工厂同构)
 */
public interface DocumentSplitter {

	/** 策略类型 */
	SplitterType type();

	/** 文本 → 分块(空白输入返回空列表) */
	List<String> split(String text);

}
