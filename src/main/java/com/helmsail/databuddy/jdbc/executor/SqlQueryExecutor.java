package com.helmsail.databuddy.jdbc.executor;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.jdbc.model.ColumnInfo;
import com.helmsail.databuddy.jdbc.model.TableData;
import com.helmsail.databuddy.jdbc.model.TableInfo;

/**
 * SQL 查询执行器:通用执行与结果转换,不含任何方言分支(方言差异全部由 SqlDialect 提供)
 */
public final class SqlQueryExecutor {

	/** 查询超时(秒) */
	private static final int QUERY_TIMEOUT_SECONDS = 30;

	/** 单次查询返回的最大行数(安全上限) */
	private static final int MAX_ROWS = 1000;

	private SqlQueryExecutor() {
	}

	/** 查询表清单,SQL 由 SqlDialect.listTablesSql 生成 */
	public static List<TableInfo> queryTables(Connection connection, String sql) {
		try (Statement statement = newStatement(connection); ResultSet rs = statement.executeQuery(sql)) {
			List<TableInfo> tables = new ArrayList<>();
			while (rs.next()) {
				tables.add(new TableInfo(rs.getString(1), rs.getString(2)));
			}
			return tables;
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表清单失败: " + e.getMessage(), e);
		}
	}

	/** 查询表结构,SQL 由 SqlDialect.listColumnsSql 生成 */
	public static List<ColumnInfo> queryColumns(Connection connection, String sql) {
		try (Statement statement = newStatement(connection); ResultSet rs = statement.executeQuery(sql)) {
			List<ColumnInfo> columns = new ArrayList<>();
			while (rs.next()) {
				String nullable = rs.getString(3);
				columns.add(new ColumnInfo(rs.getString(1), rs.getString(2),
						"Y".equalsIgnoreCase(nullable) || "YES".equalsIgnoreCase(nullable), rs.getString(4)));
			}
			return columns;
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表结构失败: " + e.getMessage(), e);
		}
	}

	/** 查询表数据,SQL 由 SqlDialect.previewTableSql 生成 */
	public static TableData queryTableData(Connection connection, String sql) {
		try (Statement statement = newStatement(connection); ResultSet rs = statement.executeQuery(sql)) {
			ResultSetMetaData metaData = rs.getMetaData();
			List<String> columns = new ArrayList<>();
			for (int i = 1; i <= metaData.getColumnCount(); i++) {
				columns.add(metaData.getColumnLabel(i));
			}
			List<List<Object>> rows = new ArrayList<>();
			while (rs.next()) {
				List<Object> row = new ArrayList<>();
				for (int i = 1; i <= columns.size(); i++) {
					row.add(rs.getObject(i));
				}
				rows.add(row);
			}
			return new TableData(columns, rows);
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询表数据失败: " + e.getMessage(), e);
		}
	}

	private static Statement newStatement(Connection connection) throws SQLException {
		Statement statement = connection.createStatement();
		statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
		statement.setMaxRows(MAX_ROWS);
		return statement;
	}

}
