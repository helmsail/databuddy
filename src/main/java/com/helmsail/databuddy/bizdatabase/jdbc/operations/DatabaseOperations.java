package com.helmsail.databuddy.bizdatabase.jdbc.operations;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import io.micrometer.observation.annotation.Observed;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbConfig;
import com.helmsail.databuddy.bizdatabase.jdbc.dialect.SqlDialect;
import com.helmsail.databuddy.bizdatabase.jdbc.dialect.SqlDialectFactory;
import com.helmsail.databuddy.bizdatabase.jdbc.executor.SqlQueryExecutor;
import com.helmsail.databuddy.bizdatabase.jdbc.model.ColumnInfo;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableData;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableInfo;
import com.helmsail.databuddy.bizdatabase.jdbc.pool.JdbcConnectionPoolFactory;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 业务库操作:单编排类——与库交互的统一入口(表清单/结构、数据预览、执行给定 SQL)。
 * 需要方言的查询按配置类型取自适配方言,统一拿池连接、交执行器执行;
 * 各类型操作流程同构(类型差异全部收敛在 SqlDialect 的语句生成与列序契约),故不按类型拆分实现,也无需外层工厂;
 * 连接池(按 url+用户名)与方言(按类型)均已在各自工厂内缓存,扩展新库只需新增方言实现,本类零改动
 */
@Component
public class DatabaseOperations {

	/** 预览行数缺省值 */
	private static final int DEFAULT_PREVIEW_LIMIT = 100;

	private final JdbcConnectionPoolFactory poolFactory;

	private final SqlDialectFactory dialectFactory;

	public DatabaseOperations(JdbcConnectionPoolFactory poolFactory, SqlDialectFactory dialectFactory) {
		this.poolFactory = poolFactory;
		this.dialectFactory = dialectFactory;
	}

	// 一行注解 = 一个 span;切面自动计时,并记录成功/失败
	/** 查看表清单 */
	@Observed(name = "db.listTables", contextualName = "查看表清单")
	public List<TableInfo> listTables(DbConfig config) {
		SqlDialect dialect = dialectFactory.get(config.getType());
		try (Connection connection = poolFactory.get(config).getConnection()) {
			return SqlQueryExecutor.queryTables(connection, dialect.listTablesSql(config.getSchema()));
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表清单失败: " + e.getMessage(), e);
		}
	}

	/** 查看表结构 */
	@Observed(name = "db.listColumns", contextualName = "查看表结构")
	public List<ColumnInfo> listColumns(DbConfig config, String table) {
		SqlDialect dialect = dialectFactory.get(config.getType());
		try (Connection connection = poolFactory.get(config).getConnection()) {
			return SqlQueryExecutor.queryColumns(connection, dialect.listColumnsSql(config.getSchema(), table));
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表结构失败: " + e.getMessage(), e);
		}
	}

	/** 预览表数据 */
	@Observed(name = "db.previewTable", contextualName = "预览表数据")
	public TableData previewTable(DbConfig config, String table, int limit) {
		SqlDialect dialect = dialectFactory.get(config.getType());
		int safeLimit = limit > 0 ? limit : DEFAULT_PREVIEW_LIMIT;
		try (Connection connection = poolFactory.get(config).getConnection()) {
			return SqlQueryExecutor.queryTableData(connection,
					dialect.previewTableSql(config.getSchema(), table, safeLimit));
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表数据失败: " + e.getMessage(), e);
		}
	}

	/** 执行给定 SQL(无需方言:SQL 已是最终形态;限行/超时由执行器统一施加) */
	@Observed(name = "db.executeSql", contextualName = "执行查询")
	public TableData executeSql(DbConfig config, String sql) {
		try (Connection connection = poolFactory.get(config).getConnection()) {
			return SqlQueryExecutor.queryTableData(connection, sql);
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "SQL 执行失败: " + e.getMessage(), e);
		}
	}

}
