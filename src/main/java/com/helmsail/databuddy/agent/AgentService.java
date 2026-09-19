package com.helmsail.databuddy.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.agent.bizdocument.AgentBizDocument;
import com.helmsail.databuddy.agent.bizdocument.AgentBizDocumentService;
import com.helmsail.databuddy.agent.bizqa.AgentBizQa;
import com.helmsail.databuddy.agent.bizqa.AgentBizQaService;
import com.helmsail.databuddy.agent.biztable.AgentBizTable;
import com.helmsail.databuddy.agent.biztable.AgentBizTableService;
import com.helmsail.databuddy.agent.bizterm.AgentBizTerm;
import com.helmsail.databuddy.agent.bizterm.AgentBizTermService;
import com.helmsail.databuddy.bizdatabase.BizDatabaseService;
import com.helmsail.databuddy.bizdatabase.BizTableRelation;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.session.SessionService;
import com.helmsail.databuddy.vectorize.IndexSourceType;
import com.helmsail.databuddy.vectorize.VectorMetadata;
import com.helmsail.databuddy.vectorize.VectorService;

import lombok.extern.slf4j.Slf4j;

/**
 * 智能体服务:agent 域的唯一对外口(身份 + 跨域横切 + 检索用例);域外只认本类,子域 CRUD 不镜像进来。
 * 检索:跨四类来源向量命中 + 按来源回源补齐(QA 补答案、术语补同义词、文档补名称);
 * 级联删除:四域逐行清(行 + 向量 + 物理文件)+ 会话域(行 + 消息)→ 向量兜底清扫 → 删 agent 行
 */
@Slf4j
@Service
public class AgentService {

	private final AgentMapper agentMapper;

	private final AgentBizTableService agentBizTableService;

	private final AgentBizTermService agentBizTermService;

	private final AgentBizQaService agentBizQaService;

	private final AgentBizDocumentService agentBizDocumentService;

	private final SessionService sessionService;

	private final VectorService vectorService;

	private final BizDatabaseService bizDatabaseService;

	public AgentService(AgentMapper agentMapper, AgentBizTableService agentBizTableService,
			AgentBizTermService agentBizTermService, AgentBizQaService agentBizQaService,
			AgentBizDocumentService agentBizDocumentService, SessionService sessionService, VectorService vectorService,
			BizDatabaseService bizDatabaseService) {
		this.agentMapper = agentMapper;
		this.agentBizTableService = agentBizTableService;
		this.agentBizTermService = agentBizTermService;
		this.agentBizQaService = agentBizQaService;
		this.agentBizDocumentService = agentBizDocumentService;
		this.sessionService = sessionService;
		this.vectorService = vectorService;
		this.bizDatabaseService = bizDatabaseService;
	}

	/** 全部智能体(新加的在前) */
	public List<Agent> list() {
		return agentMapper.selectAll();
	}

	/** 按 id 查;不存在抛 404 */
	public Agent get(long id) {
		return requireAgent(id);
	}

	/** 新增智能体:名称必填 */
	public Agent create(Agent agent) {
		validate(agent);
		agent.setId(null);
		agentMapper.insert(agent);
		log.info("agent 新增: {} (#{})", agent.getName(), agent.getId());
		return agent;
	}

	/** 修改智能体:整体覆盖(名称必填),不存在抛 404 */
	public Agent update(long id, Agent patch) {
		validate(patch);
		requireAgent(id);
		patch.setId(id);
		agentMapper.update(patch);
		return agentMapper.selectById(id);
	}

	/** 删除智能体(级联):四域逐行清(行 + 向量 + 物理文件)+ 会话域(行 + 消息)→ 向量兜底清扫 → 删 agent 行 */
	@Transactional
	public void delete(long id) {
		Agent agent = requireAgent(id);
		List<Long> tableIds = agentBizTableService.list(id).stream().map(AgentBizTable::getId).toList();
		if (!tableIds.isEmpty()) {
			agentBizTableService.unbind(id, tableIds);
		}
		for (AgentBizTerm term : agentBizTermService.list(id)) {
			agentBizTermService.delete(term.getId());
		}
		for (AgentBizQa qa : agentBizQaService.list(id)) {
			agentBizQaService.delete(qa.getId());
		}
		for (AgentBizDocument document : agentBizDocumentService.list(id)) {
			agentBizDocumentService.delete(document.getId());
		}
		sessionService.deleteByAgent(id);   // 会话域:行 + 消息
		vectorService.deleteByAgent(id);   // 兜底:清残留向量(防历史脏数据)
		agentMapper.deleteById(id);
		log.info("agent 删除(级联): {} (#{})", agent.getName(), id);
	}

	/**
	 * 检索:跨四类来源向量命中,再按来源回源补齐(QA 补答案、术语补同义词、文档补名称;表块自足)。
	 * 供域外(图节点等)消费;只回结构化块,上下文成文由调用方做
	 */
	public List<RetrievedChunk> retrieve(long agentId, String query, int topK) {
		return retrieve(agentId, query, topK, null);
	}

	/** 检索(限定来源类型;sourceTypes 空 = 全部来源):知识召回只取知识源,表块归 Schema 召回 */
	public List<RetrievedChunk> retrieve(long agentId, String query, int topK, Collection<IndexSourceType> sourceTypes) {
		requireAgent(agentId);
		List<Document> hits = vectorService.search(agentId, query, topK, sourceTypes);
		List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
		for (Document hit : hits) {
			IndexSourceType sourceType = IndexSourceType.valueOf(metadata(hit, VectorMetadata.SOURCE_TYPE));
			long sourceId = metadataLong(hit, VectorMetadata.SOURCE_ID);
			Double score = hit.getScore();
			chunks.add(new RetrievedChunk(sourceType, sourceId, score == null ? 0d : score, hit.getText(),
					extra(sourceType, sourceId)));
		}
		return chunks;
	}

	/**
	 * 取与指定表集相关的表关系:按 agent_biz_table 行定位这些表所属的业务库 → 逐库取关系 →
	 * 只保留"源表或目标表命中给定表集"的行;供表关系节点做 join 补齐(零 LLM)
	 */
	public List<BizTableRelation> relationsOf(long agentId, Collection<String> tableNames) {
		if (tableNames == null || tableNames.isEmpty()) {
			return List.of();
		}
		Set<String> names = Set.copyOf(tableNames);
		Set<Long> configIds = agentBizTableService.list(agentId)
			.stream()
			.filter(row -> names.contains(row.getTableName()))
			.map(AgentBizTable::getDatabaseConfigId)
			.collect(Collectors.toSet());
		List<BizTableRelation> relations = new ArrayList<>();
		for (Long configId : configIds) {
			for (BizTableRelation relation : bizDatabaseService.listRelations(configId)) {
				if (names.contains(relation.getSourceTableName()) || names.contains(relation.getTargetTableName())) {
					relations.add(relation);
				}
			}
		}
		log.info("表关系查询: agent={}, 表集 {} 张, 命中关系 {} 条", agentId, names.size(), relations.size());
		return relations;
	}

	/** 回源补齐:按来源取本行"不在向量里"的字段(QA 答案 / 术语同义词 / 文档名);行已删则空表 */
	private Map<String, Object> extra(IndexSourceType sourceType, long sourceId) {
		switch (sourceType) {
			case QA -> {
				AgentBizQa qa = agentBizQaService.get(sourceId);
				if (qa != null && StringUtils.hasText(qa.getContent())) {
					return Map.of("answer", qa.getContent());
				}
			}
			case BIZ_TERM -> {
				AgentBizTerm term = agentBizTermService.get(sourceId);
				if (term != null && StringUtils.hasText(term.getSynonyms())) {
					return Map.of("synonyms", term.getSynonyms());
				}
			}
			case DOCUMENT -> {
				AgentBizDocument document = agentBizDocumentService.get(sourceId);
				if (document != null) {
					return Map.of("name", document.getName());
				}
			}
			default -> {
				// 表块自足,无需回源
			}
		}
		return Map.of();
	}

	/** 取 metadata 文本值 */
	private String metadata(Document hit, String key) {
		Object value = hit.getMetadata().get(key);
		return value == null ? null : String.valueOf(value);
	}

	/** 取 metadata 数值(long) */
	private long metadataLong(Document hit, String key) {
		Object value = hit.getMetadata().get(key);
		return value instanceof Number number ? number.longValue() : 0L;
	}

	/** 取 agent;不存在抛 404 */
	private Agent requireAgent(long id) {
		Agent agent = agentMapper.selectById(id);
		if (agent == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "agent 不存在: " + id);
		}
		return agent;
	}

	/** 校验:名称必填(null 入参同样拒绝) */
	private void validate(Agent agent) {
		if (agent == null || !StringUtils.hasText(agent.getName())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "名称必填");
		}
	}

}

