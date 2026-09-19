package com.helmsail.databuddy.agent.bizdocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.agent.AgentMapper;
import com.helmsail.databuddy.agent.EmbeddingStatus;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.storage.FileStorage;
import com.helmsail.databuddy.storage.FileStorageFactory;
import com.helmsail.databuddy.storage.StorageType;
import com.helmsail.databuddy.vectorize.IndexSourceType;
import com.helmsail.databuddy.vectorize.VectorService;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档服务:agent_biz_document 行的生命周期(上传 / 修改 / 删除 / 列表)与向量化。
 * 三库联动:文件本体走 storage 包(FileStorageFactory 按 storage_type 分发),行落系统库,文本按 splitter_type 切分入向量库;
 * 文本获取:markdown 直读保结构,其余格式经 Tika 提取(自动识别编码、去 HTML 标签,提取为空按失败处理);
 * 上传与策略变更异步处理(落行 PENDING → worker 后台跑,立即返回),失败落库待手动 retryUnsynced 与定时兜底
 */
@Slf4j
@Service
public class AgentBizDocumentService {

	/** 失败原因落库长度上限(error_msg 列宽 512,留余量) */
	private static final int ERROR_MSG_MAX = 500;

	/** 文档存储子目录前缀(相对存储根;按 agent 分目录,与文档名拼出落盘路径) */
	private static final String SUB_PATH_PREFIX = "docs/";

	/** 可上传的扩展名白名单(文本类 + 常见文档格式;白名单外上传即拒,清单按需手动扩) */
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
			"txt", "md", "markdown", "csv", "sql", "json", "xml", "yml", "yaml", "log",
			"html", "htm", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp");

	/** 直读类扩展名(不经 Tika,保留原文:markdown 结构供 MARKDOWN 切分器识别) */
	private static final Set<String> VERBATIM_EXTENSIONS = Set.of("md", "markdown");

	/** 异步处理线程:单线程串行(读文件 + 批量嵌入是重 IO,按序处理即可);守护线程随进程退出 */
	private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "agent-bizdocument-worker");
		thread.setDaemon(true);
		return thread;
	});

	private final AgentBizDocumentMapper mapper;

	private final AgentMapper agentMapper;

	private final FileStorageFactory fileStorageFactory;

	private final VectorService vectorService;

	public AgentBizDocumentService(AgentBizDocumentMapper mapper, AgentMapper agentMapper,
			FileStorageFactory fileStorageFactory, VectorService vectorService) {
		this.mapper = mapper;
		this.agentMapper = agentMapper;
		this.fileStorageFactory = fileStorageFactory;
		this.vectorService = vectorService;
	}

	/** 某 agent 的文档清单 */
	public List<AgentBizDocument> list(long agentId) {
		return mapper.selectByAgent(agentId);
	}

	/**
	 * 上传文档:预检(agent 存在、名字未占用)→ 文件落存储(按 agent 分目录)→ 行落库 → worker 异步切分向量化,立即返回。
	 * name 缺省取文件名;仅接受白名单扩展名(文本类 + pdf/word/excel/ppt 等常见格式,其余直接拒绝)
	 */
	public Mono<AgentBizDocument> upload(long agentId, FilePart filePart, String name, SplitterType splitterType) {
		String docName = resolveName(name, filePart.filename());
		validateExtension(docName);
		SplitterType type = splitterType == null ? SplitterType.PARAGRAPH : splitterType;
		FileStorage storage = fileStorageFactory.get(StorageType.LOCAL);
		return Mono.fromRunnable(() -> precheck(agentId, docName))
			.subscribeOn(Schedulers.boundedElastic())
			.then(storage.store(filePart, SUB_PATH_PREFIX + agentId))
			.flatMap(path -> Mono.fromCallable(() -> insertAndTrigger(agentId, docName, type, path, storage))
				.subscribeOn(Schedulers.boundedElastic()));
	}

	/** 修改文档:改可变字段(文档名 / 切分策略);切分策略变化才重同步(文档名与向量内容无关) */
	public AgentBizDocument update(long id, AgentBizDocument patch) {
		if (patch == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档参数不能为空");
		}
		AgentBizDocument old = requireDocument(id);
		String name = StringUtils.hasText(patch.getName()) ? patch.getName().strip() : old.getName();
		SplitterType splitterType = patch.getSplitterType() == null ? old.getSplitterType() : patch.getSplitterType();
		try {
			mapper.update(id, name, splitterType);
		}
		catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档已存在: " + name);
		}
		AgentBizDocument updated = mapper.selectById(id);
		if (splitterType != old.getSplitterType()) {
			mapper.updateSyncStatus(id, EmbeddingStatus.PENDING, null);
			updated.setEmbeddingStatus(EmbeddingStatus.PENDING);
			updated.setErrorMsg(null);
			worker.execute(() -> syncById(id));
		}
		return updated;
	}

	/** 删除文档:物理删行 + 删对应向量 + 删存储文件 */
	@Transactional
	public void delete(long id) {
		AgentBizDocument old = requireDocument(id);
		mapper.deleteById(id);
		vectorService.deleteBySource(old.getAgentId(), IndexSourceType.DOCUMENT, id);
		fileStorageFactory.get(old.getStorageType()).delete(old.getStoragePath());
		log.info("文档删除: agent={}, name={} (#{})", old.getAgentId(), old.getName(), id);
	}

	/** 增量重试:仅处理未同步行(PENDING / FAILED);手动重试与定时兜底共用,幂等可反复调 */
	public void retryUnsynced(long agentId) {
		List<AgentBizDocument> rows = mapper.selectByAgent(agentId).stream()
			.filter(row -> row.getEmbeddingStatus() != EmbeddingStatus.SYNCED)
			.toList();
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizDocument row : rows) {
			syncRow(row);
		}
		log.info("文档向量化完成: agent={}, 共 {} 篇", agentId, rows.size());
	}

	/** 兜底扫尾:逐个 agent 重试未同步行(定时任务入口;无待重试行时静默) */
	public void retryUnsyncedAll() {
		List<Long> agentIds = mapper.selectAgentIdsUnsynced();
		for (Long agentId : agentIds) {
			retryUnsynced(agentId);
		}
		if (!agentIds.isEmpty()) {
			log.info("兜底重试完成: 涉及 {} 个 agent", agentIds.size());
		}
	}

	/** 停止异步线程(守护线程,正常退出时先停);未处理完的行由下次启动的定时兜底补刷 */
	@PreDestroy
	public void close() {
		worker.shutdownNow();
	}

	/** 上传先验:agent 必须存在、同 agent 下文档名未占用(在文件落盘前拦住,避免重名覆盖旧文件) */
	private void precheck(long agentId, String docName) {
		if (agentMapper.selectById(agentId) == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "agent 不存在: " + agentId);
		}
		if (mapper.selectByAgentAndName(agentId, docName) != null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档已存在: " + docName);
		}
	}

	/** 建行并触发后台处理(在文件落盘后调用);建行失败清掉刚落盘的文件,不留孤儿 */
	private AgentBizDocument insertAndTrigger(long agentId, String docName, SplitterType splitterType, String path,
			FileStorage storage) {
		AgentBizDocument document = new AgentBizDocument();
		document.setAgentId(agentId);
		document.setName(docName);
		document.setStorageType(storage.type());
		document.setStoragePath(path);
		document.setSplitterType(splitterType);
		document.setEmbeddingStatus(EmbeddingStatus.PENDING);
		try {
			mapper.insert(document);
		}
		catch (DuplicateKeyException e) {
			// 并发同名的兜底:文件路径已被既有行的文件占用,删除会破坏旧文件,保留
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档已存在: " + docName);
		}
		catch (Exception e) {
			deleteQuietly(storage, path);
			throw e;
		}
		worker.execute(() -> syncById(document.getId()));
		log.info("文档上传: agent={}, name={} (#{})", agentId, docName, document.getId());
		return document;
	}

	/** 异步入口:按 id 重载行(跨线程不共享对象;行已被删则跳过) */
	private void syncById(long id) {
		AgentBizDocument document = mapper.selectById(id);
		if (document == null) {
			return;
		}
		syncRow(document);
	}

	/**
	 * 单条同步:读文件原文 → 按行内策略切分入向量 → 落状态;失败不抛出,FAILED + 原因落库。
	 * synchronized:worker 与定时任务可能同时捞到同一行(此时仍是 PENDING),串行化避免重复写入
	 */
	private synchronized void syncRow(AgentBizDocument document) {
		try {
			String content = readContent(document);
			vectorService.index(document.getAgentId(), IndexSourceType.DOCUMENT, document.getId(),
					document.getSplitterType(), content);
			mapper.updateSyncStatus(document.getId(), EmbeddingStatus.SYNCED, null);
			log.info("文档向量写入: agent={}, name={} (#{})", document.getAgentId(), document.getName(),
					document.getId());
		}
		catch (Exception e) {
			log.warn("文档向量化失败: agent={}, name={}, 原因={}", document.getAgentId(), document.getName(),
					e.getMessage());
			mapper.updateSyncStatus(document.getId(), EmbeddingStatus.FAILED, truncate(e.getMessage()));
		}
	}

	/** 读文件文本:markdown 直读保原文,其余经 Tika 提取(自动识别编码 / 去 HTML 标签);提取为空按失败处理 */
	private String readContent(AgentBizDocument document) {
		FileStorage storage = fileStorageFactory.get(document.getStorageType());
		Resource resource = storage.getResource(document.getStoragePath());
		if (VERBATIM_EXTENSIONS.contains(extension(document.getName()))) {
			try (InputStream in = resource.getInputStream()) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			catch (IOException e) {
				throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取文件失败: " + e.getMessage(), e);
			}
		}
		List<Document> documents = new TikaDocumentReader(resource).get();
		String content = documents.isEmpty() ? null : documents.get(0).getText();
		if (!StringUtils.hasText(content)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "未能从文件提取到文本: " + document.getName());
		}
		return content;
	}

	/** 取文档行;不存在抛 404 */
	private AgentBizDocument requireDocument(long id) {
		AgentBizDocument document = mapper.selectById(id);
		if (document == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在: " + id);
		}
		return document;
	}

	/** 文档名归一:显式名字优先,缺省用上传文件名 */
	private String resolveName(String name, String filename) {
		String docName = StringUtils.hasText(name) ? name.strip() : filename;
		if (!StringUtils.hasText(docName)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档名不能为空");
		}
		return docName.strip();
	}

	/** 扩展名白名单校验:白名单外上传即拒(清单见 SUPPORTED_EXTENSIONS) */
	private void validateExtension(String docName) {
		if (!SUPPORTED_EXTENSIONS.contains(extension(docName))) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "暂不支持的文件类型: " + docName);
		}
	}

	/** 取小写扩展名(无点则为空串) */
	private String extension(String docName) {
		int dot = docName.lastIndexOf('.');
		return dot < 0 ? "" : docName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** 删除文件失败只告警(用于失败清理路径,不掩盖原始异常) */
	private void deleteQuietly(FileStorage storage, String path) {
		try {
			storage.delete(path);
		}
		catch (Exception e) {
			log.warn("清理文件失败: {} ({})", path, e.getMessage());
		}
	}

	/** 失败原因截断到列宽上限(NULL 安全) */
	private String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() <= ERROR_MSG_MAX ? message : message.substring(0, ERROR_MSG_MAX);
	}

}

