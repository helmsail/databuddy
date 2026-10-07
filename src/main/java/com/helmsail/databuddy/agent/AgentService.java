package com.helmsail.databuddy.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.context.event.EventListener;
import org.springframework.http.codec.multipart.FilePart;
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
import com.helmsail.databuddy.aimodel.EmbeddingModelSwitchedEvent;
import com.helmsail.databuddy.bizdatabase.BizDatabaseConfig;
import com.helmsail.databuddy.bizdatabase.BizDatabaseService;
import com.helmsail.databuddy.bizdatabase.BizTableRelation;
import com.helmsail.databuddy.bizdatabase.jdbc.config.DbType;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.session.SessionService;
import com.helmsail.databuddy.vectorize.KnowledgeType;
import com.helmsail.databuddy.vectorize.VectorMetadata;
import com.helmsail.databuddy.vectorize.VectorPresence;
import com.helmsail.databuddy.vectorize.VectorService;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 智能体服务:agent 域的唯一对外口(身份 + 跨域横切 + 检索用例 + 知识子域全量门面);域外(含 HTTP 层)只认本类。
 * 知识子域操作(表 / 术语 / 问答 / 文档 / 向量盘点)全部薄转发,逻辑归各子域 Service,本类不加工;
 * 检索:跨四类来源向量命中 + 按来源回源补齐(QA 补答案、文档补名称;术语/表块内容自足);
 * 级联删除:四域逐行清(行 + 向量 + 物理文件)+ 会话域(行 + 消息)→ 向量兜底清扫 → 删 agent 行;
 * 模型切换事件:术语 / 问答 / 文档已同步行标记失效(原因=切换),交重试管线在新分区重建;表域无状态字段,由人工刷新入口覆盖
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
			agentBizTermService.delete(id, term.getId());
		}
		for (AgentBizQa qa : agentBizQaService.list(id)) {
			agentBizQaService.delete(id, qa.getId());
		}
		for (AgentBizDocument document : agentBizDocumentService.list(id)) {
			agentBizDocumentService.delete(id, document.getId());
		}
		sessionService.deleteSessionsByAgent(id);   // 会话域:行 + 消息
		vectorService.deleteByDims(id, null, null, null);   // 兜底:清残留向量(防历史脏数据)
		agentMapper.deleteById(id);
		log.info("agent 删除(级联): {} (#{})", agent.getName(), id);
	}

	/** EMBEDDING 模型切换(事件):把每个 agent 三类知识(术语 / 问答 / 文档)的已同步行标记失败(原因=模型切换),交手动重试与定时任务在新分区重建;表域无状态字段,由人工刷新入口覆盖;旧分区向量保留,切回即恢复 */
	@EventListener
	public void onEmbeddingModelSwitched(EmbeddingModelSwitchedEvent event) {
		String reason = event.reason();
		List<Agent> agents = agentMapper.selectAll();
		for (Agent agent : agents) {
			agentBizTermService.invalidateSynced(agent.getId(), reason);
			agentBizQaService.invalidateSynced(agent.getId(), reason);
			agentBizDocumentService.invalidateSynced(agent.getId(), reason);
		}
		log.info("模型切换失效标记完成: {} 个 agent 的知识待重建 ({} -> {})", agents.size(), event.previousModel(),
				event.currentModel());
	}

	/**
	 * 检索:跨四类知识向量命中,再按知识条目回源补齐(QA 补答案、文档补名称;术语与表块内容自足)。
	 * 供域外(图节点等)消费;只回结构化块,上下文成文由调用方做
	 */
	public List<RetrievedChunk> retrieve(long agentId, String query, int topK) {
		return retrieve(agentId, query, topK, null);
	}

	/** 检索(限定单一知识类型;knowledgeType 空 = 全部类型):召回按类型逐次调用、各得独立 topK;表块归 Schema 召回 */
	public List<RetrievedChunk> retrieve(long agentId, String query, int topK, KnowledgeType knowledgeType) {
		requireAgent(agentId);
		List<Document> hits = vectorService.search(agentId, query, topK, knowledgeType);
		List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
		for (Document hit : hits) {
			KnowledgeType hitType = KnowledgeType.valueOf(metadata(hit, VectorMetadata.KNOWLEDGE_TYPE));
			long knowledgeId = metadataLong(hit, VectorMetadata.KNOWLEDGE_ID);
			Double score = hit.getScore();
			chunks.add(new RetrievedChunk(hitType, knowledgeId, score == null ? 0d : score, hit.getText(),
					extra(agentId, hitType, knowledgeId)));
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

	/** 数据分析目标库:配置 id + 方言文本(图内 SQL 组节点共用:提示词用方言、执行用连接) */
	public record DatabaseTarget(long configId, String dialect) {
	}

	/**
	 * 解析智能体分析目标库:按召回表定位所属库配置(命中表所属库优先;无命中时仅当绑定表同属一库取唯一);
	 * 判不出返回 null(由节点侧写终止语);零 LLM
	 */
	public DatabaseTarget databaseTargetOf(long agentId, Collection<String> tableNames) {
		requireAgent(agentId);
		List<AgentBizTable> rows = agentBizTableService.list(agentId);
		if (rows.isEmpty()) {
			log.warn("无法判定分析目标库: agent={}, 未绑定任何数据表", agentId);
			return null;
		}
		Set<String> names = tableNames == null || tableNames.isEmpty() ? Set.of() : Set.copyOf(tableNames);
		AgentBizTable hit = rows.stream().filter(row -> names.contains(row.getTableName())).findFirst().orElse(null);
		if (hit == null) {
			Set<Long> configIds = rows.stream().map(AgentBizTable::getDatabaseConfigId).collect(Collectors.toSet());
			if (configIds.size() != 1) {
				log.warn("无法判定分析目标库: agent={}, 候选库 {} 个, 召回表均未命中绑定", agentId, configIds.size());
				return null;
			}
			hit = rows.get(0);
		}
		BizDatabaseConfig config = bizDatabaseService.getConfig(hit.getDatabaseConfigId());
		return new DatabaseTarget(config.getId(), dialect(config.getDbType()));
	}

	// ============ 知识子域门面(薄转发:逻辑在各子域 Service,本类不加工) ============

	// ---- 表绑定 ----

	/** 表绑定清单 */
	public List<AgentBizTable> listTables(long agentId) {
		return agentBizTableService.list(agentId);
	}

	/** 绑定业务表(校验库与表存在;已绑定幂等跳过;首刷由前端连带调刷新入口) */
	public void bindTables(long agentId, long databaseConfigId, List<String> tableNames) {
		agentBizTableService.bind(agentId, databaseConfigId, tableNames);
	}

	/** 解绑(删行 + 删向量;id 不属于该 agent 的静默跳过) */
	public void unbindTables(long agentId, List<Long> ids) {
		agentBizTableService.unbind(agentId, ids);
	}

	/** 表向量刷新(人工入口:绑定后 / 结构变化 / 模型切换后使用前调用) */
	public void syncTables(long agentId) {
		agentBizTableService.sync(agentId);
	}

	// ---- 术语 ----

	/** 术语清单 */
	public List<AgentBizTerm> listTerms(long agentId) {
		return agentBizTermService.list(agentId);
	}

	/** 新增术语(落库后立即同步向量) */
	public AgentBizTerm addTerm(long agentId, AgentBizTerm term) {
		return agentBizTermService.add(agentId, term);
	}

	/** 修改术语(立即重同步) */
	public AgentBizTerm updateTerm(long agentId, long id, AgentBizTerm term) {
		return agentBizTermService.update(agentId, id, term);
	}

	/** 删除术语(行 + 向量) */
	public void deleteTerm(long agentId, long id) {
		agentBizTermService.delete(agentId, id);
	}

	/** 术语向量化重试:仅 PENDING / FAILED 行 */
	public void retryTerms(long agentId) {
		agentBizTermService.retryUnsynced(agentId);
	}

	// ---- 问答 ----

	/** 问答清单 */
	public List<AgentBizQa> listQa(long agentId) {
		return agentBizQaService.list(agentId);
	}

	/** 新增问答(落库后立即同步问题向量;答案可后补) */
	public AgentBizQa addQa(long agentId, AgentBizQa qa) {
		return agentBizQaService.add(agentId, qa);
	}

	/** 修改问答(仅问题变化才重同步) */
	public AgentBizQa updateQa(long agentId, long id, AgentBizQa qa) {
		return agentBizQaService.update(agentId, id, qa);
	}

	/** 删除问答(行 + 向量) */
	public void deleteQa(long agentId, long id) {
		agentBizQaService.delete(agentId, id);
	}

	/** 问答向量化重试:仅 PENDING / FAILED 行 */
	public void retryQa(long agentId) {
		agentBizQaService.retryUnsynced(agentId);
	}

	// ---- 文档 ----

	/** 文档清单 */
	public List<AgentBizDocument> listDocuments(long agentId) {
		return agentBizDocumentService.list(agentId);
	}

	/** 上传文档(落行 + 落文件后立即返回,后台队列异步向量化) */
	public Mono<AgentBizDocument> uploadDocument(long agentId, FilePart file, String name, SplitterType splitterType) {
		return agentBizDocumentService.upload(agentId, file, name, splitterType);
	}

	/** 修改文档(改名 / 换切分策略;换策略自动重入向量) */
	public AgentBizDocument updateDocument(long agentId, long id, AgentBizDocument document) {
		return agentBizDocumentService.update(agentId, id, document);
	}

	/** 删除文档(行 + 向量 + 物理文件) */
	public void deleteDocument(long agentId, long id) {
		agentBizDocumentService.delete(agentId, id);
	}

	/** 文档向量化重试:仅 PENDING / FAILED 行 */
	public void retryDocuments(long agentId) {
		agentBizDocumentService.retryUnsynced(agentId);
	}

	// ---- 向量盘点(运维) ----

	/** 向量情况盘点(agent × 模型 × 知识类型的存在记录) */
	public List<VectorPresence> vectorOverview() {
		return vectorService.vectorOverview();
	}

	/** 删 (agent, 模型, 知识类型) 三维度向量;返回删除块数(运维口:回收历史分区) */
	public int deleteVectors(long agentId, String model, KnowledgeType knowledgeType) {
		return vectorService.deleteByDims(agentId, model, knowledgeType, null);
	}

	/** 库类型 → 提示词用方言名 */
	private String dialect(DbType dbType) {
		return dbType == DbType.MYSQL ? "MySQL" : dbType.name();
	}

	/** 回源补齐:按知识类型取本行"不在向量里"的字段(QA 答案 / 文档名);行已删则空表 */
	private Map<String, Object> extra(long agentId, KnowledgeType knowledgeType, long knowledgeId) {
		switch (knowledgeType) {
			case QA -> {
				AgentBizQa qa = agentBizQaService.list(agentId).stream()
					.filter(row -> row.getId() == knowledgeId)
					.findFirst()
					.orElse(null);
				if (qa != null && StringUtils.hasText(qa.getContent())) {
					return Map.of("answer", qa.getContent());
				}
			}
			case DOCUMENT -> {
				AgentBizDocument document = agentBizDocumentService.list(agentId).stream()
					.filter(row -> row.getId() == knowledgeId)
					.findFirst()
					.orElse(null);
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

