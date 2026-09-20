package com.helmsail.databuddy.storage.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.storage.FileStorage;
import com.helmsail.databuddy.storage.StorageProperties;
import com.helmsail.databuddy.storage.StorageType;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 本地文件存储:文件保存在配置的根目录下,所有路径必须落在根目录内
 */
@Component
public class LocalFileStorage implements FileStorage {

	/** 访问 URL 前缀(getUrl 生成用;下载入口走 agent 域端点,此形态留待 OSS/CDN 场景) */
	private static final String URL_PREFIX = "/files/";

	private final StorageProperties properties;

	public LocalFileStorage(StorageProperties properties) {
		this.properties = properties;
	}

	@Override
	public StorageType type() {
		return StorageType.LOCAL;
	}

	@Override
	public Mono<String> store(FilePart filePart, String subPath, String filename) {
		return Mono.defer(() -> {
			String relative = relativePath(subPath, filename);
			Path target = resolve(relative);
			return Mono.fromCallable(() -> Files.createDirectories(target.getParent()))
				.subscribeOn(Schedulers.boundedElastic())
				.then(Mono.defer(() -> filePart.transferTo(target)))
				.thenReturn(relative);
		});
	}

	@Override
	public void delete(String path) {
		Path target = resolve(path);
		try {
			Files.deleteIfExists(target);
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除文件失败: " + e.getMessage(), e);
		}
	}

	@Override
	public String getUrl(String path) {
		return URL_PREFIX + path;
	}

	@Override
	public Resource getResource(String path) {
		Path target = resolve(path);
		if (!Files.exists(target)) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在: " + path);
		}
		return new FileSystemResource(target);
	}

	/** 由 subPath 与文件名拼出相对路径;文件名只取最后一段,防路径穿越 */
	private String relativePath(String subPath, String filename) {
		String name = "";
		if (filename != null && !filename.isBlank()) {
			Path namePath = Path.of(filename.replace('\\', '/')).getFileName();
			name = namePath == null ? "" : namePath.toString();
		}
		if (name.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文件名不能为空");
		}
		String relative = subPath == null || subPath.isBlank() ? name : subPath.replace('\\', '/') + "/" + name;
		return Path.of(relative).normalize().toString().replace('\\', '/');
	}

	/** 将相对路径解析到根目录内,越界(../ 逃逸)直接拒绝 */
	private Path resolve(String path) {
		Path root = Path.of(properties.getLocalRootPath()).toAbsolutePath().normalize();
		Path target = root.resolve(path).normalize();
		if (!target.startsWith(root)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "非法的文件路径: " + path);
		}
		return target;
	}

}
