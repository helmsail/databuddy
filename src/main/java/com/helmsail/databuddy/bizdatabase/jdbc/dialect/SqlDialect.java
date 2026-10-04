package com.helmsail.databuddy.bizdatabase.jdbc.dialect;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbType;

/**
 * SQL 方言:封装"因数据库而异"的查询语句生成,实现类只写差异部分。
 * 库 / 模式定位不在参数中传递:由连接 URL 与各库的连接上下文语义承担(如 MySQL 的 DATABASE())
 */
public interface SqlDialect {

	/** 支持的数据库类型 */
	DbType type();

	/**
	 * 查询表清单,结果列序必须为:table_name, table_comment(comment 可为 null)
	 */
	String listTablesSql();

	/**
	 * 查询表结构,结果列序必须为:column_name, data_type, is_nullable(Y/YES 表示可空), column_comment(comment 可为 null)
	 */
	String listColumnsSql(String table);

	/** 字符串字面量(防注入),默认单引号 + 双写转义 */
	default String literal(String text) {
		return "'" + text.replace("'", "''") + "'";
	}

}
