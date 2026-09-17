package com.helmsail.databuddy.jdbc.operations;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.jdbc.config.DbType;

/**
 * 数据库操作工厂:按数据库类型获取对应的 DatabaseOperations(由 Spring 注入所有实现)
 */
@Component
public class DatabaseOperationsFactory {

	private final Map<DbType, DatabaseOperations> operationsByType = new EnumMap<>(DbType.class);

	public DatabaseOperationsFactory(List<DatabaseOperations> operations) {
		operations.forEach(operation -> operationsByType.put(operation.type(), operation));
	}

	public DatabaseOperations get(DbType type) {
		DatabaseOperations operations = operationsByType.get(type);
		if (operations == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的数据库类型: " + type);
		}
		return operations;
	}

}
