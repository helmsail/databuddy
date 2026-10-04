package com.helmsail.databuddy.agent;

import java.util.Map;

import com.helmsail.databuddy.vectorize.KnowledgeType;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 检索命中块(AgentService.retrieve 返回):向量命中原文 + 按知识条目回源补齐。
 * content 为入向量时的块文本;extra 装"不在向量里"的回源字段(缺省空表):
 * QA 补 answer(答案)、DOCUMENT 补 name(文档名)、TERM/TABLE 无(内容自足)
 */
@Data
@AllArgsConstructor
public class RetrievedChunk {

	/** 知识类型(四类知识子域) */
	private KnowledgeType knowledgeType;

	/** 知识行主键(回源锚点) */
	private long knowledgeId;

	/** 相似度(向量库未打分时为 0) */
	private double score;

	/** 入向量时的块文本 */
	private String content;

	/** 回源补齐的"不在向量里"的字段(缺省空表) */
	private Map<String, Object> extra;

}
