package com.helmsail.databuddy.agent.bizterm;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.agent.AgentMapper;
import com.helmsail.databuddy.agent.EmbeddingStatus;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.vectorize.IndexSourceType;
import com.helmsail.databuddy.vectorize.VectorService;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;

/**
 * 术语服务:agent_biz_term 行的生命周期(新增 / 修改 / 删除 / 列表)与单条同步向量化。
 * CRUD 即触发(同步等待结果):向量化内容 = 术语 + 释义(WHOLE 一块),走 VectorService 唯一口;
 * 同步失败不阻断落库,FAILED + 原因落库,手动 retryUnsynced 与定时兜底共用
 */
@Slf4j
@Service
public class AgentBizTermService {

	/** 失败原因落库长度上限(error_msg 列宽 512,留余量) */
	private static final int ERROR_MSG_MAX = 500;

	private final AgentBizTermMapper mapper;

	private final AgentMapper agentMapper;

	private final VectorService vectorService;

	public AgentBizTermService(AgentBizTermMapper mapper, AgentMapper agentMapper, VectorService vectorService) {
		this.mapper = mapper;
		this.agentMapper = agentMapper;
		this.vectorService = vectorService;
	}

	/** 某 agent 的术语清单 */
	public List<AgentBizTerm> list(long agentId) {
		return mapper.selectByAgent(agentId);
	}

	/** 按 id 查;不存在返回 null(检索回源用) */
	public AgentBizTerm get(long id) {
		return mapper.selectById(id);
	}

	/** 新增术语:agent 必须存在;落库后立即同步向量(失败不阻断,FAILED + 原因落库待重试) */
	public AgentBizTerm add(long agentId, AgentBizTerm term) {
		if (agentMapper.selectById(agentId) == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "agent 不存在: " + agentId);
		}
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

	/** 修改术语:改可变字段(术语 / 同义词 / 释义)后立即重同步 */
	public AgentBizTerm update(long id, AgentBizTerm term) {
		requireTerm(id);
		validate(term);
		term.setId(id);
		try {
			mapper.update(term);
		}
		catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "术语已存在: " + term.getBusinessTerm());
		}
		AgentBizTerm updated = mapper.selectById(id);
		syncRow(updated);
		return updated;
	}

	/** 删除术语:物理删行 + 删对应向量 */
	@Transactional
	public void delete(long id) {
		AgentBizTerm old = requireTerm(id);
		mapper.deleteById(id);
		vectorService.deleteBySource(old.getAgentId(), IndexSourceType.BIZ_TERM, id);
		log.info("术语删除: agent={}, term={} (#{})", old.getAgentId(), old.getBusinessTerm(), id);
	}

	/** 全量重建:全部术语逐行向量化(重启后内存向量库丢失的恢复入口;失败行落 FAILED 待重试) */
	public void rebuildAll(long agentId) {
		List<AgentBizTerm> rows = mapper.selectByAgent(agentId);
		for (AgentBizTerm row : rows) {
			syncRow(row);
		}
		log.info("术语全量重建完成: agent={}, 共 {} 条", agentId, rows.size());
	}

	/** 增量重试:仅处理未同步行(PENDING / FAILED);手动重试与定时兜底共用,幂等可反复调 */
	public void retryUnsynced(long agentId) {
		List<AgentBizTerm> rows = mapper.selectByAgent(agentId).stream()
			.filter(row -> row.getEmbeddingStatus() != EmbeddingStatus.SYNCED)
			.toList();
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizTerm row : rows) {
			syncRow(row);
		}
		log.info("术语向量化完成: agent={}, 共 {} 条", agentId, rows.size());
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

	/** 单条同步:拼文本 → 索引 → 落状态;失败不抛出,FAILED + 原因落库 */
	private void syncRow(AgentBizTerm term) {
		try {
			String content = buildContent(term);
			vectorService.index(term.getAgentId(), IndexSourceType.BIZ_TERM, term.getId(), SplitterType.WHOLE, content);
			mapper.updateSyncStatus(term.getId(), EmbeddingStatus.SYNCED, null);
			term.setEmbeddingStatus(EmbeddingStatus.SYNCED);
			term.setErrorMsg(null);
			log.info("术语向量写入: agent={}, term={} (#{})", term.getAgentId(), term.getBusinessTerm(), term.getId());
		}
		catch (Exception e) {
			String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
			log.warn("术语向量化失败: agent={}, term={}", term.getAgentId(), term.getBusinessTerm(), e);
			mapper.updateSyncStatus(term.getId(), EmbeddingStatus.FAILED, truncate(reason));
			term.setEmbeddingStatus(EmbeddingStatus.FAILED);
			term.setErrorMsg(truncate(reason));
		}
	}

	/** 向量化文本:术语 + 释义;释义缺失只省略、不失败(内容兜底) */
	private String buildContent(AgentBizTerm term) {
		StringBuilder content = new StringBuilder("术语: ").append(term.getBusinessTerm());
		if (StringUtils.hasText(term.getDescription())) {
			content.append('\n').append("释义: ").append(term.getDescription());
		}
		return content.toString();
	}

	/** 取术语行;不存在抛 404 */
	private AgentBizTerm requireTerm(long id) {
		AgentBizTerm term = mapper.selectById(id);
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

