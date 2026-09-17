package com.helmsail.databuddy.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 文件存储配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "databuddy.storage")
public class StorageProperties {

	/** 本地存储根目录(见 databuddy.storage.local-root-path) */
	private String localRootPath;

}
