package com.helmsail.databuddy.agent.bizqa;

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
 * 问答服务:agent_biz_qa 行的生命周期(新增 / 修改 / 删除 / 列表)与单条同步向量化。
 * 仅问题入向量(WHOLE 一块,答案留库回源):改答案不触发重同步,改问题才重同步;
 * 同步失败不阻断落库,FAILED + 原因落库,手动 retryUnsynced 与定时兜底共用
 */
@Slf4j
@Service
public class AgentBizQaService {

	/** 失败原因落库长度上限(error_msg 列宽 512,留余量) */
	private static final int ERROR_MSG_MAX = 500;

	private final AgentBizQaMapper mapper;

	private final AgentMapper agentMapper;

	private final VectorService vectorService;

	public AgentBizQaService(AgentBizQaMapper mapper, AgentMapper agentMapper, VectorService vectorService) {
		this.mapper = mapper;
		this.agentMapper = agentMapper;
		this.vectorService = vectorService;
	}

	/** 某 agent 的问答清单 */
	public List<AgentBizQa> list(long agentId) {
		return mapper.selectByAgent(agentId);
	}

	/** 按 id 查;不存在返回 null(检索回源用) */
	public AgentBizQa get(long id) {
		return mapper.selectById(id);
	}

	/** 新增问答:agent 必须存在;落库后立即同步问题向量(失败不阻断,FAILED + 原因落库待重试) */
	public AgentBizQa add(long agentId, AgentBizQa qa) {
		if (agentMapper.selectById(agentId) == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "agent 不存在: " + agentId);
		}
		validate(qa);
		qa.setId(null);
		qa.setAgentId(agentId);
		qa.setEmbeddingStatus(EmbeddingStatus.PENDING);
		qa.setErrorMsg(null);
		try {
			mapper.insert(qa);
		}
		catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "问题已存在: " + qa.getQuestion());
		}
		syncRow(qa);
		return qa;
	}

	/** 修改问答:改可变字段(问题 / 答案);仅问题变化才重同步(答案不入向量) */
	public AgentBizQa update(long id, AgentBizQa qa) {
		AgentBizQa old = requireQa(id);
		validate(qa);
		qa.setId(id);
		try {
			mapper.update(qa);
		}
		catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "问题已存在: " + qa.getQuestion());
		}
		AgentBizQa updated = mapper.selectById(id);
		if (!updated.getQuestion().equals(old.getQuestion())) {
			syncRow(updated);
		}
		return updated;
	}

	/** 删除问答:物理删行 + 删对应向量 */
	@Transactional
	public void delete(long id) {
		AgentBizQa old = requireQa(id);
		mapper.deleteById(id);
		vectorService.deleteBySource(old.getAgentId(), IndexSourceType.QA, id);
		log.info("问答删除: agent={}, question={} (#{})", old.getAgentId(), old.getQuestion(), id);
	}

	/** 增量重试:仅处理未同步行(PENDING / FAILED);手动重试与定时兜底共用,幂等可反复调 */
	public void retryUnsynced(long agentId) {
		List<AgentBizQa> rows = mapper.selectByAgent(agentId).stream()
			.filter(row -> row.getEmbeddingStatus() != EmbeddingStatus.SYNCED)
			.toList();
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizQa row : rows) {
			syncRow(row);
		}
		log.info("问答向量化完成: agent={}, 共 {} 条", agentId, rows.size());
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
	private void syncRow(AgentBizQa qa) {
		try {
			String content = buildContent(qa);
			vectorService.index(qa.getAgentId(), IndexSourceType.QA, qa.getId(), SplitterType.WHOLE, content);
			mapper.updateSyncStatus(qa.getId(), EmbeddingStatus.SYNCED, null);
			qa.setEmbeddingStatus(EmbeddingStatus.SYNCED);
			qa.setErrorMsg(null);
			log.info("问答向量写入: agent={}, question={} (#{})", qa.getAgentId(), qa.getQuestion(), qa.getId());
		}
		catch (Exception e) {
			log.warn("问答向量化失败: agent={}, question={}, 原因={}", qa.getAgentId(), qa.getQuestion(), e.getMessage());
			mapper.updateSyncStatus(qa.getId(), EmbeddingStatus.FAILED, truncate(e.getMessage()));
			qa.setEmbeddingStatus(EmbeddingStatus.FAILED);
			qa.setErrorMsg(truncate(e.getMessage()));
		}
	}

	/** 向量化文本:仅问题(答案不入向量,命中后回源 MySQL 取 content) */
	private String buildContent(AgentBizQa qa) {
		return qa.getQuestion();
	}

	/** 取问答行;不存在抛 404 */
	private AgentBizQa requireQa(long id) {
		AgentBizQa qa = mapper.selectById(id);
		if (qa == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "问答不存在: " + id);
		}
		return qa;
	}

	/** 校验:问题必填(答案可空,后续可补且不必重同步) */
	private void validate(AgentBizQa qa) {
		if (qa == null || !StringUtils.hasText(qa.getQuestion())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "问题必填");
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

