package com.helmsail.databuddy.bizdatabase.jdbc.operations;

import java.util.List;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbConfig;
import com.helmsail.databuddy.bizdatabase.jdbc.config.DbType;
import com.helmsail.databuddy.bizdatabase.jdbc.model.ColumnInfo;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableData;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableInfo;

/**
 * 业务数据库操作:查看表结构与表内容,每种数据库类型一个实现
 */
public interface DatabaseOperations {

	/** 支持的数据库类型 */
	DbType type();

	/** 查看表清单 */
	List<TableInfo> listTables(DbConfig config);

	/** 查看表结构 */
	List<ColumnInfo> listColumns(DbConfig config, String table);

	/** 预览表数据 */
	TableData previewTable(DbConfig config, String table, int limit);

}
