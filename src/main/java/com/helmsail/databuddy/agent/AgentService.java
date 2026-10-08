package com.helmsail.databuddy.agent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.bottom.aimodel.EmbeddingModelSwitchedEvent;
import com.helmsail.databuddy.middle.bizdocument.AgentBizDocument;
import com.helmsail.databuddy.middle.bizdocument.AgentBizDocumentService;
import com.helmsail.databuddy.middle.bizqa.AgentBizQa;
import com.helmsail.databuddy.middle.bizqa.AgentBizQaService;
import com.helmsail.databuddy.middle.biztable.AgentBizTable;
import com.helmsail.databuddy.middle.biztable.AgentBizTableService;
import com.helmsail.databuddy.middle.bizterm.AgentBizTerm;
import com.helmsail.databuddy.middle.bizterm.AgentBizTermService;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.middle.graph.GraphService;
import com.helmsail.databuddy.middle.graph.GraphSseChunk;
import com.helmsail.databuddy.middle.memory.AgentMemory;
import com.helmsail.databuddy.middle.memory.MemoryService;
import com.helmsail.databuddy.middle.session.Session;
import com.helmsail.databuddy.middle.session.SessionService;
import com.helmsail.databuddy.bottom.vectorize.KnowledgeType;
import com.helmsail.databuddy.bottom.vectorize.RetrievedChunk;
import com.helmsail.databuddy.bottom.vectorize.VectorPresence;
import com.helmsail.databuddy.bottom.vectorize.VectorService;
import com.helmsail.databuddy.bottom.vectorize.splitter.SplitterType;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能体服务:入口层(HTTP / MCP)共用的 agent 域门面(身份 + 图执行 + 跨域横切 + 知识子域全量门面);入口只认本类。
 * 图执行:run / resume / clear 薄转发 GraphService(依赖单向:本类 → 图域;图节点直连各子域服务取数,不再反向依赖本类);
 * 知识子域操作(表 / 术语 / 问答 / 文档 / 记忆 / 向量盘点)全部薄转发,逻辑归各子域 Service,本类不加工;agent 存在性校验在本类前置;
 * 级联删除:四域逐行清(行 + 向量 + 物理文件)+ 会话域(行 + 消息 + 跨图记忆/摘要)+ agent 沉淀记忆 → 向量兜底清扫 → 删 agent 行 → 停图运行 + 释放检查点;
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

	private final MemoryService memoryService;

	private final SessionService sessionService;

	private final VectorService vectorService;

	/** 图执行(单向:图节点已直连子域服务,无环,无需断环) */
	private final GraphService graphService;

	public AgentService(AgentMapper agentMapper, AgentBizTableService agentBizTableService,
			AgentBizTermService agentBizTermService, AgentBizQaService agentBizQaService,
			AgentBizDocumentService agentBizDocumentService, MemoryService memoryService,
			SessionService sessionService, VectorService vectorService, GraphService graphService) {
		this.agentMapper = agentMapper;
		this.agentBizTableService = agentBizTableService;
		this.agentBizTermService = agentBizTermService;
		this.agentBizQaService = agentBizQaService;
		this.agentBizDocumentService = agentBizDocumentService;
		this.memoryService = memoryService;
		this.sessionService = sessionService;
		this.vectorService = vectorService;
		this.graphService = graphService;
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

	/** 删除智能体(级联):四域逐行清(行 + 向量 + 物理文件)→ 停图运行 / 释放检查点 → 会话域(行 + 消息 + 跨图记忆/摘要)+ agent 沉淀记忆 → 向量兜底清扫 → 删 agent 行 */
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
		List<String> sessionIds = sessionService.listSessions(id).stream().map(Session::getId).toList();   // 删前收集会话键
		sessionIds.forEach(graphService::clear);   // 图域:先废弃图(停现场 + 释放检查点,幂等;防跑完写回幽灵记忆)
		sessionService.deleteSessionsByAgent(id);   // 会话域:行 + 消息 + 跨图记忆/摘要(随删联动)
		memoryService.deleteByAgent(id);   // agent 沉淀记忆
		vectorService.deleteByDims(id, null, null, null);   // 兜底:清残留向量(防历史脏数据)
		agentMapper.deleteById(id);
		log.info("agent 删除(级联): {} (#{})", agent.getName(), id);
	}

	// ============ 图执行(agent 核心能力:HTTP 入口在 AgentController,编排走 GraphService) ============

	/** 发起执行(SSE,唯一入口):先校验 agent(失败以流内 error 帧回传);nl2sqlMode = 轻档(MCP 同口,图内按参数决定走法) */
	public Flux<ServerSentEvent<GraphSseChunk>> run(long agentId, String input, String sessionId, boolean planReview,
			boolean nl2sqlMode) {
		try {
			requireAgent(agentId);
		}
		catch (BusinessException e) {
			return Flux.just(GraphSseChunk.errorFrame(sessionId, e.getMessage()));
		}
		return graphService.run(agentId, input, sessionId, planReview, nl2sqlMode);
	}

	/** 恢复挂起轮(SSE):校验与断点续跑全在 GraphService(检查点域) */
	public Flux<ServerSentEvent<GraphSseChunk>> resume(String sessionId, boolean approved, String feedback) {
		return graphService.resume(sessionId, approved, feedback);
	}

	/** 清某会话的图(外部停止 = 图废弃:现场作废 + 检查点释放;级联删除同口;幂等) */
	public void clear(String sessionId) {
		graphService.clear(sessionId);
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
	 * 检索联调合并口(管理界面"检索测试"专用):逐域独立检索后合并(术语 / 问答 / 文档 / 表块;各域回源字段齐全)。
	 * 图链路不经本口——节点直连各子域服务
	 */
	public List<RetrievedChunk> retrieve(long agentId, String query, int topK) {
		requireAgent(agentId);
		List<RetrievedChunk> chunks = new ArrayList<>();
		chunks.addAll(agentBizTermService.retrieve(agentId, query, topK));
		chunks.addAll(agentBizQaService.retrieve(agentId, query, topK));
		chunks.addAll(agentBizDocumentService.retrieve(agentId, query, topK));
		chunks.addAll(agentBizTableService.retrieve(agentId, query, topK));
		return chunks;
	}

	// ============ 知识子域门面(薄转发:逻辑在各子域 Service,本类不加工) ============

	// ---- 表绑定 ----

	/** 表绑定清单 */
	public List<AgentBizTable> listTables(long agentId) {
		return agentBizTableService.list(agentId);
	}

	/** 绑定业务表(agent 必须存在;校验库与表存在;已绑定幂等跳过;首刷由前端连带调刷新入口) */
	public void bindTables(long agentId, long databaseConfigId, List<String> tableNames) {
		requireAgent(agentId);
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

	/** 新增术语(agent 必须存在;落库后立即同步向量) */
	public AgentBizTerm addTerm(long agentId, AgentBizTerm term) {
		requireAgent(agentId);
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

	/** 新增问答(agent 必须存在;落库后立即同步问题向量;答案可后补) */
	public AgentBizQa addQa(long agentId, AgentBizQa qa) {
		requireAgent(agentId);
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

	/** 上传文档(agent 必须存在;落行 + 落文件后立即返回,后台队列异步向量化) */
	public Mono<AgentBizDocument> uploadDocument(long agentId, FilePart file, String name, SplitterType splitterType) {
		requireAgent(agentId);
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

	// ---- 记忆(用户面:AI 沉淀的临时记忆,可看可改可删) ----

	/** 记忆清单(沉淀先后) */
	public List<AgentMemory> listMemories(long agentId) {
		return memoryService.list(agentId);
	}

	/** 修改记忆内容(用户修正 AI 沉淀) */
	public AgentMemory updateMemory(long agentId, long id, String content) {
		return memoryService.update(agentId, id, content);
	}

	/** 删除记忆(清理不再需要的沉淀) */
	public void deleteMemory(long agentId, long id) {
		memoryService.delete(agentId, id);
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

