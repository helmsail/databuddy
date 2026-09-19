package com.helmsail.databuddy.bizdatabase.jdbc.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 列信息
 */
@Data
@AllArgsConstructor
public class ColumnInfo {

	/** 列名 */
	private String name;

	/** 数据类型 */
	private String dataType;

	/** 是否可空 */
	private boolean nullable;

	/** 列注释(可能为 null) */
	private String comment;

}
