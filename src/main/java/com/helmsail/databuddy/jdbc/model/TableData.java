package com.helmsail.databuddy.jdbc.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 表数据(预览结果)
 */
@Data
@AllArgsConstructor
public class TableData {

	/** 列名列表 */
	private List<String> columns;

	/** 数据行,每行与 columns 顺序对应 */
	private List<List<Object>> rows;

}
