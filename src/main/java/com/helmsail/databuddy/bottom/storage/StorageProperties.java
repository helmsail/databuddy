package com.helmsail.databuddy.bottom.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

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

	/** 上传文档大小上限(入口按 part 头预检 + 落盘后复核实际大小,两处拦截) */
	private DataSize maxFileSize = DataSize.ofMegabytes(20);

}
