package com.helmsail.databuddy.bizdatabase.jdbc.dialect;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbType;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 方言工厂:按数据库类型索引所有 SqlDialect 实现(由 Spring 自动注入)
 */
@Component
public class SqlDialectFactory {

	private final Map<DbType, SqlDialect> dialects = new EnumMap<>(DbType.class);

	public SqlDialectFactory(List<SqlDialect> dialectList) {
		dialectList.forEach(dialect -> dialects.put(dialect.type(), dialect));
	}

	public SqlDialect get(DbType type) {
		SqlDialect dialect = dialects.get(type);
		if (dialect == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的数据库类型: " + type);
		}
		return dialect;
	}

}
