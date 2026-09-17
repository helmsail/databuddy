package com.helmsail.databuddy.jdbc.config;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 数据库类型
 */
public enum DbType {

	MYSQL;

	/** 从字符串解析(如外部配置传入 "mysql"),未知类型抛出业务异常 */
	public static DbType from(String type) {
		for (DbType value : values()) {
			if (value.name().equalsIgnoreCase(type)) {
				return value;
			}
		}
		throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的数据库类型: " + type);
	}

}
