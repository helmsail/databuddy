package com.helmsail.databuddy.bottom.bizdatabase.jdbc.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * SQL 结果集(任意查询的结果):列标题 + 行数据,行内与列顺序对应
 */
@Data
@AllArgsConstructor
public class TableData {

	/** 列名列表 */
	private List<String> columns;

	/** 数据行,每行与 columns 顺序对应 */
	private List<List<Object>> rows;

}
