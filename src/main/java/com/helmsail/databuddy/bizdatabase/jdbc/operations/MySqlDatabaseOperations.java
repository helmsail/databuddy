package com.helmsail.databuddy.bizdatabase.jdbc.operations;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbType;
import com.helmsail.databuddy.bizdatabase.jdbc.dialect.SqlDialectFactory;
import com.helmsail.databuddy.bizdatabase.jdbc.pool.JdbcConnectionPoolFactory;

/**
 * MySQL 数据库操作
 */
@Component
public class MySqlDatabaseOperations extends AbstractDatabaseOperations {

	public MySqlDatabaseOperations(JdbcConnectionPoolFactory poolFactory, SqlDialectFactory dialectFactory) {
		super(DbType.MYSQL, poolFactory, dialectFactory);
	}

}
