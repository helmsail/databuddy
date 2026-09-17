package com.helmsail.databuddy.jdbc.dialect;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.jdbc.config.DbType;

/**
 * MySQL SQL 方言
 */
@Component
public class MySqlSqlDialect implements SqlDialect {

	@Override
	public DbType type() {
		return DbType.MYSQL;
	}

	@Override
	public String listTablesSql(String schema) {
		return "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = "
				+ literal(schema);
	}

	@Override
	public String listColumnsSql(String schema, String table) {
		return "SELECT column_name, column_type, is_nullable, column_comment FROM information_schema.columns "
				+ "WHERE table_schema = " + literal(schema) + " AND table_name = " + literal(table)
				+ " ORDER BY ordinal_position";
	}

	@Override
	public String previewTableSql(String schema, String table, int limit) {
		return "SELECT * FROM " + qualified(schema, table) + " LIMIT " + limit;
	}

	@Override
	public String identifier(String name) {
		return "`" + name.replace("`", "``") + "`";
	}

}
