package com.helmsail.databuddy.agent.bizdocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.agent.AgentMapper;
import com.helmsail.databuddy.agent.EmbeddingStatus;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.storage.LocalFileStorage;
import com.helmsail.databuddy.vectorize.KnowledgeType;
import com.helmsail.databuddy.vectorize.VectorService;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档服务:agent_biz_document 行的生命周期(上传 / 修改 / 删除 / 列表)与向量化。
 * 三库联动(上传):行先落系统库(唯一键裁决重名)→ 文件同步落 storage(落盘失败撤行,不留悬挂)→ 文本经 vectorSyncExecutor 队列异步切分入向量库;
 * 文本获取:markdown 直读保结构,其余格式经 Tika 提取(自动识别编码、去 HTML 标签,提取为空按失败处理);
 * 上传与策略变更异步处理(行落 PENDING → vectorSyncExecutor 后台跑,立即返回),失败落库待手动 retryUnsynced 与定时兜底
 */
@Slf4j
@Service
public class AgentBizDocumentService {

	/** 失败原因落库长度上限(error_msg 列宽 512,留余量) */
	private static final int ERROR_MSG_MAX = 500;

	/** 文档存储子目录前缀(相对存储根;按 agent 分目录,与文档名拼出落盘路径) */
	private static final String SUB_PATH_PREFIX = "docs/";

	/** 可上传的扩展名白名单(最常用:文本 txt/md + pdf/word/excel/ppt;白名单外上传即拒,清单按需手动扩) */
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
			"txt", "md", "markdown", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx");

	/** 直读类扩展名(不经 Tika,保留原文:markdown 结构供 MARKDOWN 切分器识别) */
	private static final Set<String> VERBATIM_EXTENSIONS = Set.of("md", "markdown");

	private final AgentBizDocumentMapper mapper;

	private final AgentMapper agentMapper;

	private final LocalFileStorage fileStorage;

	private final VectorService vectorService;

	/** 向量化后台队列(装配见 async 包):单线程 FIFO 串行,活过请求 */
	private final TaskExecutor vectorSyncExecutor;

	public AgentBizDocumentService(AgentBizDocumentMapper mapper, AgentMapper agentMapper,
			LocalFileStorage fileStorage, VectorService vectorService,
			@Qualifier("vectorSyncExecutor") TaskExecutor vectorSyncExecutor) {
		this.mapper = mapper;
		this.agentMapper = agentMapper;
		this.fileStorage = fileStorage;
		this.vectorService = vectorService;
		this.vectorSyncExecutor = vectorSyncExecutor;
	}

	/** 某 agent 的文档清单 */
	public List<AgentBizDocument> list(long agentId) {
		return mapper.selectByAgent(agentId);
	}

	/**
	 * 上传文档:预检(agent 存在)→ 行先落库(唯一键裁决重名:重名即拒,不碰文件)→ 文件同步落存储(按 agent 分目录,
	 * 落盘名 = 文档名;落盘失败撤行)→ vectorSyncExecutor 队列异步切分向量化,立即返回。
	 * name 缺省取文件名;仅接受白名单扩展名(文本类 + pdf/word/excel/ppt,其余直接拒绝)
	 */
	public Mono<AgentBizDocument> upload(long agentId, FilePart filePart, String name, SplitterType splitterType) {
		// 文档名归一与校验:显式名字优先(缺省取上传文件名),不含路径分隔符,扩展名在白名单内
		String rawName = StringUtils.hasText(name) ? name : filePart.filename();
		if (!StringUtils.hasText(rawName)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档名不能为空");
		}
		String docName = rawName.strip();
		if (docName.contains("/") || docName.contains("\\")) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档名不能包含路径分隔符: " + docName);
		}
		if (!SUPPORTED_EXTENSIONS.contains(extension(docName))) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "暂不支持的文件类型: " + docName);
		}
		SplitterType type = splitterType == null ? SplitterType.PARAGRAPH : splitterType;
		// 与 storage 落盘同一拼法(subPath + 文件名),行内路径与磁盘一致
		String path = SUB_PATH_PREFIX + agentId + "/" + docName;
		return Mono.fromRunnable(() -> {
			if (agentMapper.selectById(agentId) == null) {
				throw new BusinessException(ErrorCode.NOT_FOUND, "agent 不存在: " + agentId);
			}
		})
			.subscribeOn(Schedulers.boundedElastic())
			.then(Mono.fromCallable(() -> {
				// 行先落库:唯一键裁决重名(重名即拒,不碰文件);返回已回填自增 id 的行
				AgentBizDocument document = new AgentBizDocument();
				document.setAgentId(agentId);
				document.setName(docName);
				document.setStoragePath(path);
				document.setSplitterType(type);
				document.setEmbeddingStatus(EmbeddingStatus.PENDING);
				try {
					mapper.insert(document);
				}
				catch (DuplicateKeyException e) {
					throw new BusinessException(ErrorCode.INVALID_INPUT, "文档已存在: " + docName);
				}
				return document;
			})
				.subscribeOn(Schedulers.boundedElastic()))
			.flatMap(document -> fileStorage.store(filePart, SUB_PATH_PREFIX + agentId, docName)
				.doOnError(e -> rollbackQuietly(document))
				.doOnSuccess(stored -> {
					vectorSyncExecutor.execute(() -> syncById(agentId, document.getId()));
					log.info("文档上传: agent={}, name={} (#{})", agentId, docName, document.getId());
				})
				.thenReturn(document));
	}

	/** 修改文档(按 agent + id 定位):改可变字段(文档名 / 切分策略);切分策略变化才重同步(文档名与向量内容无关) */
	public AgentBizDocument update(long agentId, long id, AgentBizDocument patch) {
		if (patch == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档参数不能为空");
		}
		AgentBizDocument old = requireDocument(agentId, id);
		String name = StringUtils.hasText(patch.getName()) ? patch.getName().strip() : old.getName();
		SplitterType splitterType = patch.getSplitterType() == null ? old.getSplitterType() : patch.getSplitterType();
		try {
			mapper.update(agentId, id, name, splitterType);
		}
		catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "文档已存在: " + name);
		}
		AgentBizDocument updated = requireDocument(agentId, id);
		if (splitterType != old.getSplitterType()) {
			writeStatus(updated, EmbeddingStatus.PENDING, null);
			vectorSyncExecutor.execute(() -> syncById(agentId, id));
		}
		return updated;
	}

	/** 删除文档(按 agent + id 定位):物理删行 + 删对应向量 + 删存储文件 */
	@Transactional
	public void delete(long agentId, long id) {
		AgentBizDocument old = requireDocument(agentId, id);
		mapper.deleteById(agentId, id);
		vectorService.deleteEntry(old.getAgentId(), KnowledgeType.DOCUMENT, id);
		fileStorage.delete(old.getStoragePath());
		log.info("文档删除: agent={}, name={} (#{})", old.getAgentId(), old.getName(), id);
	}

	/** 增量重试:处理某 agent 全部未同步行(PENDING / FAILED 各查一次);手动重试与定时兜底共用,幂等可反复调 */
	public void retryUnsynced(long agentId) {
		List<AgentBizDocument> rows = mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.PENDING);
		rows.addAll(mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.FAILED));
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizDocument row : rows) {
			syncRow(row);
		}
		log.info("文档向量化完成: agent={}, 共 {} 篇", agentId, rows.size());
	}

	/** 模型切换失效:把已同步行标记 FAILED(原因给定),交重试 / 定时兜底在新模型分区重建(旧分区向量保留,切回即恢复) */
	public void invalidateSynced(long agentId, String reason) {
		List<AgentBizDocument> rows = mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.SYNCED);
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizDocument row : rows) {
			writeStatus(row, EmbeddingStatus.FAILED,
					reason.length() <= ERROR_MSG_MAX ? reason : reason.substring(0, ERROR_MSG_MAX));
		}
		log.info("文档模型切换失效: agent={}, 共 {} 篇待重建", agentId, rows.size());
	}

	/** 落盘失败回滚:撤掉刚插入的行 + 清可能残留的部分文件(两步均只告警,不掩盖原始异常) */
	private void rollbackQuietly(AgentBizDocument document) {
		try {
			mapper.deleteById(document.getAgentId(), document.getId());
		}
		catch (Exception e) {
			log.warn("回滚文档行失败: #{} ({})", document.getId(), e.getMessage());
		}
		try {
			fileStorage.delete(document.getStoragePath());
		}
		catch (Exception e) {
			log.warn("清理文件失败: {} ({})", document.getStoragePath(), e.getMessage());
		}
	}

	/** 异步入口:按 agent + id 重载行(跨线程不共享对象;行已被删则跳过) */
	private void syncById(long agentId, long id) {
		AgentBizDocument document = findRow(agentId, id);
		if (document == null) {
			return;
		}
		syncRow(document);
	}

	/**
	 * 单条同步:读文件原文 → 按行内策略切分入向量 → 落状态;失败不抛出,FAILED + 原因落库。
	 * synchronized:vectorSyncExecutor 队列与定时任务可能同时捞到同一行(此时仍是 PENDING),串行化避免重复写入
	 */
	private synchronized void syncRow(AgentBizDocument document) {
		try {
			String content = readContent(document);
			vectorService.index(document.getAgentId(), KnowledgeType.DOCUMENT, document.getId(),
					document.getSplitterType(), content);
			writeStatus(document, EmbeddingStatus.SYNCED, null);
			log.info("文档向量写入: agent={}, name={} (#{})", document.getAgentId(), document.getName(),
					document.getId());
		}
		catch (Exception e) {
			String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
			log.warn("文档向量化失败: agent={}, name={}", document.getAgentId(), document.getName(), e);
			writeStatus(document, EmbeddingStatus.FAILED,
					reason.length() <= ERROR_MSG_MAX ? reason : reason.substring(0, ERROR_MSG_MAX));
		}
	}

	/** 状态回执(CAS + 迁移校验):仅当行仍为读取时状态才落新态;0 行 = 状态已变或行已删,回执未生效 */
	private void writeStatus(AgentBizDocument document, EmbeddingStatus to, String errorMsg) {
		EmbeddingStatus from = document.getEmbeddingStatus();
		if (from == null || !from.canTransitionTo(to)) {
			log.warn("非法状态迁移被挡: {} -> {} (#{})", from, to, document.getId());
			return;
		}
		int rows = mapper.updateSyncStatus(document.getAgentId(), document.getId(), from, to, errorMsg);
		if (rows == 0) {
			log.warn("状态回执未生效(状态已变或行已删): #{} {} -> {}", document.getId(), from, to);
			return;
		}
		document.setEmbeddingStatus(to);
		document.setErrorMsg(errorMsg);
	}

	/** 读文件文本:markdown 直读保原文,其余经 Tika 提取(自动识别编码 / 去 HTML 标签);提取为空按失败处理 */
	private String readContent(AgentBizDocument document) {
		Resource resource = fileStorage.getResource(document.getStoragePath());
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

	/** 从清单筛取指定 id 的行;不存在返回 null(单行定位统一由清单筛取:单 agent 行数为小集合) */
	private AgentBizDocument findRow(long agentId, long id) {
		return mapper.selectByAgent(agentId).stream()
			.filter(document -> document.getId() == id)
			.findFirst()
			.orElse(null);
	}

	/** 取文档行(按 agent + id);不存在抛 404 */
	private AgentBizDocument requireDocument(long agentId, long id) {
		AgentBizDocument document = findRow(agentId, id);
		if (document == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在: " + id);
		}
		return document;
	}

	/** 取小写扩展名(无点则为空串) */
	private String extension(String docName) {
		int dot = docName.lastIndexOf('.');
		return dot < 0 ? "" : docName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

}

