package com.helmsail.databuddy.storage;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * 文件存储工厂:按存储类型获取实现(由 Spring 注入所有实现)
 */
@Component
public class FileStorageFactory {

	private final Map<StorageType, FileStorage> storages = new EnumMap<>(StorageType.class);

	public FileStorageFactory(List<FileStorage> storageList) {
		storageList.forEach(storage -> storages.put(storage.type(), storage));
	}

	/** 按存储类型获取实现 */
	public FileStorage get(StorageType type) {
		FileStorage storage = storages.get(type);
		if (storage == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "不支持的存储类型: " + type);
		}
		return storage;
	}

}
