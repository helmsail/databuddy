package com.helmsail.databuddy.middle.bizterm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.bottom.vectorize.EmbeddingStatus;
import com.helmsail.databuddy.bottom.vectorize.KnowledgeType;
import com.helmsail.databuddy.bottom.vectorize.RetrievedChunk;
import com.helmsail.databuddy.bottom.vectorize.VectorService;
import com.helmsail.databuddy.bottom.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;

/**
 * 术语服务:agent_biz_term 行的生命周期(新增 / 修改 / 删除 / 列表)、单条同步向量化与图侧检索(retrieve)。
 * CRUD 即触发(同步等待结果):向量化内容 = 术语 + 同义词 + 释义(WHOLE 一块),走 VectorService 唯一口;
 * 同步失败不阻断落库,FAILED + 原因落库,手动 retryUnsynced 与定时兜底共用
 */
@Slf4j
@Service
public class AgentBizTermService {

	/** 失败原因落库长度上限(error_msg 列宽 512,留余量) */
	private static final int ERROR_MSG_MAX = 500;

	private final AgentBizTermMapper mapper;

	private final VectorService vectorService;

	public AgentBizTermService(AgentBizTermMapper mapper, VectorService vectorService) {
		this.mapper = mapper;
		this.vectorService = vectorService;
	}

	/** 某 agent 的术语清单 */
	public List<AgentBizTerm> list(long agentId) {
		return mapper.selectByAgent(agentId);
	}

	/** 图侧检索:问题向量命中术语块(术语 + 同义词 + 释义齐入索引,内容自足,无需回源) */
	public List<RetrievedChunk> retrieve(long agentId, String query, int topK) {
		List<Document> hits = vectorService.search(agentId, query, topK, KnowledgeType.TERM);
		List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
		for (Document hit : hits) {
			chunks.add(RetrievedChunk.of(hit, Map.of()));
		}
		return chunks;
	}

	/** 新增术语(agent 存在性由入口校验);落库后立即同步向量(失败不阻断,FAILED + 原因落库待重试) */
	public AgentBizTerm add(long agentId, AgentBizTerm term) {
		validate(term);
		term.setId(null);
		term.setAgentId(agentId);
		term.setEmbeddingStatus(EmbeddingStatus.PENDING);
		term.setErrorMsg(null);
		try {
			mapper.insert(term);
		}
		catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "术语已存在: " + term.getBusinessTerm());
		}
		syncRow(term);
		return term;
	}

	/** 修改术语(按 agent + id 定位):改可变字段(术语 / 同义词 / 释义)后立即重同步 */
	public AgentBizTerm update(long agentId, long id, AgentBizTerm term) {
		requireTerm(agentId, id);
		validate(term);
		try {
			mapper.update(agentId, id, term.getBusinessTerm(), term.getSynonyms(), term.getDescription());
		}
		catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "术语已存在: " + term.getBusinessTerm());
		}
		AgentBizTerm updated = requireTerm(agentId, id);
		syncRow(updated);
		return updated;
	}

	/** 删除术语(按 agent + id 定位):物理删行 + 删对应向量 */
	@Transactional
	public void delete(long agentId, long id) {
		AgentBizTerm old = requireTerm(agentId, id);
		mapper.deleteById(agentId, id);
		vectorService.deleteEntry(old.getAgentId(), KnowledgeType.TERM, id);
		log.info("术语删除: agent={}, term={} (#{})", old.getAgentId(), old.getBusinessTerm(), id);
	}

	/** 有未同步行(PENDING / FAILED)的 agent 去重清单(定时兜底扇出用) */
	public List<Long> unsyncedAgentIds() {
		return mapper.selectUnsyncedAgentIds(EmbeddingStatus.PENDING, EmbeddingStatus.FAILED);
	}

	/** 增量重试:处理某 agent 全部未同步行(PENDING / FAILED 各查一次);手动重试与定时兜底共用,幂等可反复调 */
	public void retryUnsynced(long agentId) {
		List<AgentBizTerm> rows = mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.PENDING);
		rows.addAll(mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.FAILED));
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizTerm row : rows) {
			syncRow(row);
		}
		log.info("术语向量化完成: agent={}, 共 {} 条", agentId, rows.size());
	}

	/** 模型切换失效:把已同步行标记 FAILED(原因给定),交重试 / 定时兜底在新模型分区重建(旧分区向量保留,切回即恢复) */
	public void invalidateSynced(long agentId, String reason) {
		List<AgentBizTerm> rows = mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.SYNCED);
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizTerm row : rows) {
			writeStatus(row, EmbeddingStatus.FAILED, truncate(reason));
		}
		log.info("术语模型切换失效: agent={}, 共 {} 条待重建", agentId, rows.size());
	}

	/** 单条同步:拼文本(术语 + 同义词 + 释义 齐入索引,规范表述与别称/简称均可召回;缺失只省略)→ 索引 → 落状态;失败不抛出,FAILED + 原因落库 */
	private void syncRow(AgentBizTerm term) {
		try {
			StringBuilder content = new StringBuilder("术语: ").append(term.getBusinessTerm());
			if (StringUtils.hasText(term.getSynonyms())) {
				content.append('\n').append("同义词: ").append(term.getSynonyms());
			}
			if (StringUtils.hasText(term.getDescription())) {
				content.append('\n').append("释义: ").append(term.getDescription());
			}
			vectorService.index(term.getAgentId(), KnowledgeType.TERM, term.getId(), SplitterType.WHOLE,
					content.toString());
			writeStatus(term, EmbeddingStatus.SYNCED, null);
			log.info("术语向量写入: agent={}, term={} (#{})", term.getAgentId(), term.getBusinessTerm(), term.getId());
		}
		catch (Exception e) {
			String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
			log.warn("术语向量化失败: agent={}, term={}", term.getAgentId(), term.getBusinessTerm(), e);
			writeStatus(term, EmbeddingStatus.FAILED, truncate(reason));
		}
	}

	/** 状态回执(CAS + 迁移校验):仅当行仍为读取时状态才落新态;0 行 = 状态已变或行已删,回执未生效 */
	private void writeStatus(AgentBizTerm term, EmbeddingStatus to, String errorMsg) {
		EmbeddingStatus from = term.getEmbeddingStatus();
		if (from == null || !from.canTransitionTo(to)) {
			log.warn("非法状态迁移被挡: {} -> {} (#{})", from, to, term.getId());
			return;
		}
		int rows = mapper.updateSyncStatus(term.getAgentId(), term.getId(), from, to, errorMsg);
		if (rows == 0) {
			log.warn("状态回执未生效(状态已变或行已删): #{} {} -> {}", term.getId(), from, to);
			return;
		}
		term.setEmbeddingStatus(to);
		term.setErrorMsg(errorMsg);
	}

	/** 取术语行(按 agent + id,清单筛取);不存在抛 404 */
	private AgentBizTerm requireTerm(long agentId, long id) {
		AgentBizTerm term = mapper.selectByAgent(agentId).stream()
			.filter(row -> row.getId() == id)
			.findFirst()
			.orElse(null);
		if (term == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "术语不存在: " + id);
		}
		return term;
	}

	/** 校验:术语必填 */
	private void validate(AgentBizTerm term) {
		if (term == null || !StringUtils.hasText(term.getBusinessTerm())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "术语必填");
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

