package com.helmsail.databuddy.jdbc.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表信息
 */
@Data
@AllArgsConstructor
public class TableInfo {

	/** 表名 */
	private String name;

	/** 表注释(可能为 null) */
	private String comment;

}
