package com.helmsail.databuddy.storage;

import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;

import reactor.core.publisher.Mono;

/**
 * 文件存储:本地、OSS 等实现按 StorageType 由 FileStorageFactory 分发。
 * 当前仅适配 WebFlux(入参为 FilePart);非 web 场景需要时再加普通重载
 */
public interface FileStorage {

	/** 支持的存储类型 */
	StorageType type();

	/**
	 * 存储上传的文件
	 * @param filePart 上传的文件
	 * @param subPath 业务子目录,如 "docs",可为空
	 * @param filename 落盘文件名(调用方显式给定:与业务名一致并防同源覆盖,如文档名)
	 * @return 存储路径(相对根目录,如 docs/demo.txt)
	 */
	Mono<String> store(FilePart filePart, String subPath, String filename);

	/** 删除文件;不存在时静默成功 */
	void delete(String path);

	/** 获取访问 URL */
	String getUrl(String path);

	/** 获取文件资源,用于下载响应 */
	Resource getResource(String path);

}
