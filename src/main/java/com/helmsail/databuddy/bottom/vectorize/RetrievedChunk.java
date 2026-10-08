package com.helmsail.databuddy.bottom.vectorize;

import java.util.Map;

import org.springframework.ai.document.Document;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 检索命中块(各知识子域 retrieve 返回):向量命中原文 + 按知识条目回源补齐。
 * content 为入向量时的块文本;extra 装"不在向量里"的回源字段(缺省空表):
 * QA 补 answer(答案)、DOCUMENT 补 name(文档名)、TERM/TABLE 无(内容自足);
 * 元数据解析(知识类型 / 知识行主键 / 得分)统一在 of 工厂,各域 retrieve 共用
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

	/** 从向量命中构造:解析元数据、取块文本原文;extra 为回源补齐字段(缺省空表) */
	public static RetrievedChunk of(Document hit, Map<String, Object> extra) {
		return new RetrievedChunk(KnowledgeType.valueOf(metadata(hit, VectorMetadata.KNOWLEDGE_TYPE)),
				metadataLong(hit, VectorMetadata.KNOWLEDGE_ID), hit.getScore() == null ? 0d : hit.getScore(),
				hit.getText(), extra);
	}

	/** 取 metadata 文本值 */
	private static String metadata(Document hit, String key) {
		Object value = hit.getMetadata().get(key);
		return value == null ? null : String.valueOf(value);
	}

	/** 取 metadata 数值(long) */
	private static long metadataLong(Document hit, String key) {
		Object value = hit.getMetadata().get(key);
		return value instanceof Number number ? number.longValue() : 0L;
	}

}
