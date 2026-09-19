package com.helmsail.databuddy.vectorize.splitter;

import com.fasterxml.jackson.annotation.JsonCreator;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 切分策略类型(agent_biz_document.splitter_type 存 name();按内容形态选用,一个值对应 splitter 子包一个实现类)
 */
public enum SplitterType {

	/** 整段不切:已组装好的短文本(业务表 / 术语 / QA) */
	WHOLE,

	/** 段落:按空行成段,小段合并、超长硬切(中文业务文档主力策略) */
	PARAGRAPH,

	/** 标题:按 Markdown 标题成块,标题保留为上下文(md 与结构化文档) */
	MARKDOWN,

	/** Token:按 Spring AI TokenTextSplitter 默认参数(通用兜底) */
	TOKEN;

	/** 从字符串解析(如 "token"),未知类型抛出业务异常;HTTP 请求体反序列化同样走这里 */
	@JsonCreator
	public static SplitterType from(String type) {
		for (SplitterType value : values()) {
			if (value.name().equalsIgnoreCase(type)) {
				return value;
			}
		}
		throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的切分策略: " + type);
	}

}
