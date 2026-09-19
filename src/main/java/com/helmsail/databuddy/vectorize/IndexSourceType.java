package com.helmsail.databuddy.vectorize;

/**
 * 向量来源类型(写入 metadata 的 source_type;四类知识源各一,回源时据它 + source_id 定位)
 */
public enum IndexSourceType {

	BIZ_TABLE, BIZ_TERM, DOCUMENT, QA;

}
