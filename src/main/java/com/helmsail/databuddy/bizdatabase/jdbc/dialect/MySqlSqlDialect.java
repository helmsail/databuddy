package com.helmsail.databuddy.bizdatabase.jdbc.dialect;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbType;

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
	public String listTablesSql() {
		return "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = DATABASE()";
	}

	@Override
	public String listColumnsSql(String table) {
		return "SELECT column_name, column_type, is_nullable, column_comment FROM information_schema.columns "
				+ "WHERE table_schema = DATABASE() AND table_name = " + literal(table) + " ORDER BY ordinal_position";
	}

}
