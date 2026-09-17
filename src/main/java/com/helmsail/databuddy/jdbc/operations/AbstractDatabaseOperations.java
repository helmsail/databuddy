package com.helmsail.databuddy.jdbc.operations;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import io.micrometer.observation.annotation.Observed;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.jdbc.config.DbConfig;
import com.helmsail.databuddy.jdbc.config.DbType;
import com.helmsail.databuddy.jdbc.dialect.SqlDialect;
import com.helmsail.databuddy.jdbc.dialect.SqlDialectFactory;
import com.helmsail.databuddy.jdbc.executor.SqlQueryExecutor;
import com.helmsail.databuddy.jdbc.model.ColumnInfo;
import com.helmsail.databuddy.jdbc.model.TableData;
import com.helmsail.databuddy.jdbc.model.TableInfo;
import com.helmsail.databuddy.jdbc.pool.JdbcConnectionPoolFactory;

/**
 * DatabaseOperations 抽象基类:编排 连接池 + 方言 + 执行器,子类只需声明类型
 */
public abstract class AbstractDatabaseOperations implements DatabaseOperations {

	/** 预览行数缺省值 */
	private static final int DEFAULT_PREVIEW_LIMIT = 100;

	private final DbType type;

	protected final JdbcConnectionPoolFactory poolFactory;

	protected final SqlDialectFactory dialectFactory;

	protected AbstractDatabaseOperations(DbType type, JdbcConnectionPoolFactory poolFactory,
			SqlDialectFactory dialectFactory) {
		this.type = type;
		this.poolFactory = poolFactory;
		this.dialectFactory = dialectFactory;
	}

	@Override
	public DbType type() {
		return type;
	}

	@Override
	// 一行注解 = 一个 span(名字 + 标签);切面自动计时,并记录成功/失败
	@Observed(name = "db.listTables", contextualName = "查看表清单",
			lowCardinalityKeyValues = { "db.type", "mysql" })
	public List<TableInfo> listTables(DbConfig config) {
		SqlDialect dialect = dialectFactory.get(type);
		try (Connection connection = poolFactory.get(config).getConnection()) {
			return SqlQueryExecutor.queryTables(connection, dialect.listTablesSql(config.getSchema()));
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表清单失败: " + e.getMessage(), e);
		}
	}

	@Override
	@Observed(name = "db.listColumns", contextualName = "查看表结构",
			lowCardinalityKeyValues = { "db.type", "mysql" })
	public List<ColumnInfo> listColumns(DbConfig config, String table) {
		SqlDialect dialect = dialectFactory.get(type);
		try (Connection connection = poolFactory.get(config).getConnection()) {
			return SqlQueryExecutor.queryColumns(connection, dialect.listColumnsSql(config.getSchema(), table));
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表结构失败: " + e.getMessage(), e);
		}
	}

	@Override
	@Observed(name = "db.previewTable", contextualName = "预览表数据",
			lowCardinalityKeyValues = { "db.type", "mysql" })
	public TableData previewTable(DbConfig config, String table, int limit) {
		SqlDialect dialect = dialectFactory.get(type);
		int safeLimit = limit > 0 ? limit : DEFAULT_PREVIEW_LIMIT;
		try (Connection connection = poolFactory.get(config).getConnection()) {
			return SqlQueryExecutor.queryTableData(connection,
					dialect.previewTableSql(config.getSchema(), table, safeLimit));
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表数据失败: " + e.getMessage(), e);
		}
	}

}
