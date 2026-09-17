package com.helmsail.databuddy.storage;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.multipart.FilePart;

import com.helmsail.databuddy.exception.BusinessException;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件存储端到端验证(本地实现):工厂 -> 存储 -> 上传/读取/删除/路径防护
 */
@SpringBootTest
class FileStorageTests {

	@Autowired
	private FileStorageFactory factory;

	@Autowired
	private StorageProperties properties;

	@BeforeEach
	void useTempRoot() {
		properties.setLocalRootPath(Path.of(System.getProperty("java.io.tmpdir"), "dd-storage-test").toString());
	}

	@Test
	void storeReadDeleteFlow() throws Exception {
		FilePart filePart = mock(FilePart.class);
		when(filePart.filename()).thenReturn("demo.txt");
		when(filePart.transferTo(any(Path.class))).thenAnswer(invocation -> {
			Files.writeString(invocation.getArgument(0), "hello");
			return Mono.empty();
		});

		FileStorage storage = factory.get(StorageType.LOCAL);
		assertThat(storage.type()).isEqualTo(StorageType.LOCAL);

		String path = storage.store(filePart, "docs").block();
		assertThat(path).isEqualTo("docs/demo.txt");
		assertThat(storage.getUrl(path)).isEqualTo("/files/docs/demo.txt");
		assertThat(storage.getResource(path).exists()).isTrue();

		storage.delete(path);
		assertThatThrownBy(() -> storage.getResource(path)).isInstanceOf(BusinessException.class);
	}

	@Test
	void pathTraversalIsRejected() {
		FileStorage storage = factory.get(StorageType.LOCAL);
		assertThatThrownBy(() -> storage.getResource("../../escape.txt")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> storage.delete("../escape.txt")).isInstanceOf(BusinessException.class);
	}

	@Test
	void unknownTypeIsRejected() {
		assertThatThrownBy(() -> StorageType.from("unknown")).isInstanceOf(BusinessException.class);
	}

}
