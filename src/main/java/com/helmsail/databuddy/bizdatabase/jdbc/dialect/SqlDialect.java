package com.helmsail.databuddy.bizdatabase.jdbc.dialect;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbType;

/**
 * SQL 方言:封装"因数据库而异"的查询语句生成,实现类只写差异部分
 */
public interface SqlDialect {

	/** 支持的数据库类型 */
	DbType type();

	/**
	 * 查询表清单,结果列序必须为:table_name, table_comment(comment 可为 null)
	 */
	String listTablesSql(String schema);

	/**
	 * 查询表结构,结果列序必须为:column_name, data_type, is_nullable(Y/YES 表示可空), column_comment(comment 可为 null)
	 */
	String listColumnsSql(String schema, String table);

	/** 查询表数据(预览),带行数限制 */
	String previewTableSql(String schema, String table, int limit);

	/** 标识符引用,默认双引号;MySQL 等需要反引号的方言自行覆盖 */
	default String identifier(String name) {
		return "\"" + name.replace("\"", "\"\"") + "\"";
	}

	/** 字符串字面量(防注入),默认单引号 + 双写转义 */
	default String literal(String text) {
		return "'" + text.replace("'", "''") + "'";
	}

	/** schema 限定表名,如 "schema"."table";schema 为空时只返回表名 */
	default String qualified(String schema, String table) {
		return schema == null || schema.isBlank() ? identifier(table) : identifier(schema) + "." + identifier(table);
	}

}
