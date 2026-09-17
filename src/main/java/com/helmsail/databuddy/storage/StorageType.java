package com.helmsail.databuddy.storage;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 存储类型
 */
public enum StorageType {

	LOCAL;

	/** 从字符串解析(如外部配置传入 "local"),未知类型抛出业务异常 */
	public static StorageType from(String type) {
		for (StorageType value : values()) {
			if (value.name().equalsIgnoreCase(type)) {
				return value;
			}
		}
		throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的存储类型: " + type);
	}

}
