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
import com.helmsail.databuddy.vectorize.KnowledgeType;
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

	/** 修改问答(按 agent + id 定位):改可变字段(问题 / 答案);仅问题变化才重同步(答案不入向量) */
	public AgentBizQa update(long agentId, long id, AgentBizQa qa) {
		AgentBizQa old = requireQa(agentId, id);
		validate(qa);
		try {
			mapper.update(agentId, id, qa.getQuestion(), qa.getContent());
		}
		catch (DuplicateKeyException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "问题已存在: " + qa.getQuestion());
		}
		AgentBizQa updated = requireQa(agentId, id);
		if (!updated.getQuestion().equals(old.getQuestion())) {
			syncRow(updated);
		}
		return updated;
	}

	/** 删除问答(按 agent + id 定位):物理删行 + 删对应向量 */
	@Transactional
	public void delete(long agentId, long id) {
		AgentBizQa old = requireQa(agentId, id);
		mapper.deleteById(agentId, id);
		vectorService.deleteEntry(old.getAgentId(), KnowledgeType.QA, id);
		log.info("问答删除: agent={}, question={} (#{})", old.getAgentId(), old.getQuestion(), id);
	}

	/** 增量重试:处理某 agent 全部未同步行(PENDING / FAILED 各查一次);手动重试与定时兜底共用,幂等可反复调 */
	public void retryUnsynced(long agentId) {
		List<AgentBizQa> rows = mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.PENDING);
		rows.addAll(mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.FAILED));
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizQa row : rows) {
			syncRow(row);
		}
		log.info("问答向量化完成: agent={}, 共 {} 条", agentId, rows.size());
	}

	/** 模型切换失效:把已同步行标记 FAILED(原因给定),交重试 / 定时兜底在新模型分区重建(旧分区向量保留,切回即恢复) */
	public void invalidateSynced(long agentId, String reason) {
		List<AgentBizQa> rows = mapper.selectByAgentAndStatus(agentId, EmbeddingStatus.SYNCED);
		if (rows.isEmpty()) {
			return;
		}
		for (AgentBizQa row : rows) {
			writeStatus(row, EmbeddingStatus.FAILED, truncate(reason));
		}
		log.info("问答模型切换失效: agent={}, 共 {} 条待重建", agentId, rows.size());
	}

	/** 单条同步:仅问题入向量(答案留库回源)→ 索引 → 落状态;失败不抛出,FAILED + 原因落库 */
	private void syncRow(AgentBizQa qa) {
		try {
			vectorService.index(qa.getAgentId(), KnowledgeType.QA, qa.getId(), SplitterType.WHOLE,
					qa.getQuestion());
			writeStatus(qa, EmbeddingStatus.SYNCED, null);
			log.info("问答向量写入: agent={}, question={} (#{})", qa.getAgentId(), qa.getQuestion(), qa.getId());
		}
		catch (Exception e) {
			String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
			log.warn("问答向量化失败: agent={}, question={}", qa.getAgentId(), qa.getQuestion(), e);
			writeStatus(qa, EmbeddingStatus.FAILED, truncate(reason));
		}
	}

	/** 状态回执(CAS + 迁移校验):仅当行仍为读取时状态才落新态;0 行 = 状态已变或行已删,回执未生效 */
	private void writeStatus(AgentBizQa qa, EmbeddingStatus to, String errorMsg) {
		EmbeddingStatus from = qa.getEmbeddingStatus();
		if (from == null || !from.canTransitionTo(to)) {
			log.warn("非法状态迁移被挡: {} -> {} (#{})", from, to, qa.getId());
			return;
		}
		int rows = mapper.updateSyncStatus(qa.getAgentId(), qa.getId(), from, to, errorMsg);
		if (rows == 0) {
			log.warn("状态回执未生效(状态已变或行已删): #{} {} -> {}", qa.getId(), from, to);
			return;
		}
		qa.setEmbeddingStatus(to);
		qa.setErrorMsg(errorMsg);
	}

	/** 取问答行(按 agent + id,清单筛取);不存在抛 404 */
	private AgentBizQa requireQa(long agentId, long id) {
		AgentBizQa qa = mapper.selectByAgent(agentId).stream()
			.filter(row -> row.getId() == id)
			.findFirst()
			.orElse(null);
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

