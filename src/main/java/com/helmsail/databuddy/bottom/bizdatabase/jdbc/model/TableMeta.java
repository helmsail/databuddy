package com.helmsail.databuddy.bottom.bizdatabase.jdbc.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表元数据(表清单条目)
 */
@Data
@AllArgsConstructor
public class TableMeta {

	/** 表名 */
	private String name;

	/** 表注释(可能为 null) */
	private String comment;

}
