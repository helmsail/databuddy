package com.helmsail.databuddy.agent;

import java.util.Map;

import com.helmsail.databuddy.vectorize.IndexSourceType;

/**
 * 检索命中块(AgentService.retrieve 返回):向量命中原文 + 按来源回源补齐。
 * content 为入向量时的块文本;extra 装"不在向量里"的回源字段(缺省空表):
 * QA 补 answer(答案)、BIZ_TERM 补 synonyms(同义词)、DOCUMENT 补 name(文档名)、BIZ_TABLE 无(块自足)
 */
public record RetrievedChunk(IndexSourceType sourceType, long sourceId, double score, String content,
		Map<String, Object> extra) {
}
