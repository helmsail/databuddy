package com.helmsail.databuddy.agent;

import java.util.List;
import java.util.concurrent.Callable;

import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.agent.bizdocument.AgentBizDocument;
import com.helmsail.databuddy.agent.bizqa.AgentBizQa;
import com.helmsail.databuddy.agent.biztable.AgentBizTable;
import com.helmsail.databuddy.agent.bizterm.AgentBizTerm;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.result.ApiResponse;
import com.helmsail.databuddy.vectorize.KnowledgeType;
import com.helmsail.databuddy.vectorize.VectorPresence;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 智能体入口:全部动作薄转发 AgentService(域内唯一门面,含向量分区运维),本类只做 HTTP 层;
 * 成功返回统一信封(ApiResponse),错误由全局异常处理器转同形信封。
 * 线程边界:触达模型 / 向量的动作含阻塞式 Spring AI 调用,统一经 reactive/reactiveVoid 移入弹性线程
 * (在 WebFlux 事件循环线程上会被 Reactor 拒绝:block() not supported in thread reactor-http-epoll,已实证)
 */
@RestController
@RequestMapping("/agent")
@CrossOrigin(origins = "*")
public class AgentController {

	private final AgentService agentService;

	public AgentController(AgentService agentService) {
		this.agentService = agentService;
	}

	/**
	 * 同步动作移入弹性线程执行:触达模型 / 向量的动作走阻塞式 Spring AI 调用,在 WebFlux 事件循环线程上
	 * 会直接抛错且耗时较长(长退避重试会拖死事件循环);统一 subscribeOn(boundedElastic) 后按普通阻塞线程运行
	 */
	private static <T> Mono<ApiResponse<T>> reactive(Callable<T> action) {
		return Mono.fromCallable(action)
			.subscribeOn(Schedulers.boundedElastic())
			.map(ApiResponse::success);
	}

	private static Mono<ApiResponse<Void>> reactiveVoid(Runnable action) {
		return Mono.fromRunnable(action)
			.subscribeOn(Schedulers.boundedElastic())
			.thenReturn(ApiResponse.success());
	}

	// ============ agent 本体 ============

	/** agent 列表(新加的在前) */
	@GetMapping
	public ApiResponse<List<Agent>> list() {
		return ApiResponse.success(agentService.list());
	}

	/** agent 详情;不存在 404 */
	@GetMapping("/{agentId}")
	public ApiResponse<Agent> get(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentService.get(agentId));
	}

	/** 新增 agent(名称必填) */
	@PostMapping
	public ApiResponse<Agent> create(@RequestBody Agent agent) {
		return ApiResponse.success(agentService.create(agent));
	}

	/** 修改 agent(整体覆盖:名称必填) */
	@PostMapping("/{agentId}")
	public ApiResponse<Agent> update(@PathVariable("agentId") long agentId, @RequestBody Agent agent) {
		return ApiResponse.success(agentService.update(agentId, agent));
	}

	/** 删除 agent(级联:四类知识源的行 / 向量 / 物理文件;向量清理触达模型,走弹性线程) */
	@DeleteMapping("/{agentId}")
	public Mono<ApiResponse<Void>> delete(@PathVariable("agentId") long agentId) {
		return reactiveVoid(() -> agentService.delete(agentId));
	}

	/** 检索联调口(节点侧走 AgentService.retrieve;返回结构化块,含回源字段,不做上下文成文;检索含向量调用,走弹性线程) */
	@GetMapping("/{agentId}/retrieve")
	public Mono<ApiResponse<List<RetrievedChunk>>> retrieve(@PathVariable("agentId") long agentId,
			@RequestParam("query") String query, @RequestParam(name = "topK", defaultValue = "5") int topK) {
		return reactive(() -> agentService.retrieve(agentId, query, topK));
	}

	// ============ 向量分区(vectorize) ============

	/** 向量情况:全部 agent × 模型 × 知识类型的存在记录(基于向量库元数据原始包装;失效 agentId 由展示侧对照 agent 列表判定) */
	@GetMapping("/vectors")
	public Mono<ApiResponse<List<VectorPresence>>> vectors() {
		return reactive(agentService::vectorOverview);
	}

	/** 手动删除 (agent, 模型, 知识类型) 三维度向量(三维度由外部传入,入参校验在本层;向量操作走弹性线程);data = 删除块数 */
	@DeleteMapping("/{agentId}/vectors")
	public Mono<ApiResponse<Integer>> deleteVectors(@PathVariable("agentId") long agentId,
			@RequestParam("model") String model, @RequestParam("knowledgeType") String knowledgeType) {
		return reactive(() -> {
			if (!StringUtils.hasText(model)) {
				throw new BusinessException(ErrorCode.INVALID_INPUT, "模型名不能为空");
			}
			return agentService.deleteVectors(agentId, model, KnowledgeType.from(knowledgeType));
		});
	}

	// ============ 表绑定(biztable) ============

	/** 表绑定清单 */
	@GetMapping("/{agentId}/biz-tables")
	public ApiResponse<List<AgentBizTable>> listTables(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentService.listTables(agentId));
	}

	/** 绑定业务表(校验库与表存在;已绑定幂等跳过;首刷由前端连带调刷新入口) */
	@PostMapping("/{agentId}/biz-tables")
	public ApiResponse<Void> bindTables(@PathVariable("agentId") long agentId, @RequestBody BindTablesRequest request) {
		agentService.bindTables(agentId, request.databaseConfigId(), request.tableNames());
		return ApiResponse.success();
	}

	/** 解绑(删行 + 删向量;id 不属于该 agent 的静默跳过;向量清理触达模型,走弹性线程) */
	@DeleteMapping("/{agentId}/biz-tables")
	public Mono<ApiResponse<Void>> unbindTables(@PathVariable("agentId") long agentId, @RequestBody List<Long> ids) {
		return reactiveVoid(() -> agentService.unbindTables(agentId, ids));
	}

	/** 表向量刷新:实时查业务库结构重刷全部绑定表(人工入口:绑定后 / 结构变化 / 模型切换后使用前调用;模型调用走弹性线程) */
	@PostMapping("/{agentId}/biz-tables/sync")
	public Mono<ApiResponse<Void>> syncTables(@PathVariable("agentId") long agentId) {
		return reactiveVoid(() -> agentService.syncTables(agentId));
	}

	// ============ 业务术语(bizterm) ============

	/** 术语清单 */
	@GetMapping("/{agentId}/terms")
	public ApiResponse<List<AgentBizTerm>> listTerms(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentService.listTerms(agentId));
	}

	/** 新增术语(落库后立即同步向量;模型调用走弹性线程) */
	@PostMapping("/{agentId}/terms")
	public Mono<ApiResponse<AgentBizTerm>> addTerm(@PathVariable("agentId") long agentId, @RequestBody AgentBizTerm term) {
		return reactive(() -> agentService.addTerm(agentId, term));
	}

	/** 修改术语(术语 / 同义词 / 释义,立即重同步;模型调用走弹性线程) */
	@PostMapping("/{agentId}/terms/{id}")
	public Mono<ApiResponse<AgentBizTerm>> updateTerm(@PathVariable("agentId") long agentId, @PathVariable("id") long id,
			@RequestBody AgentBizTerm term) {
		return reactive(() -> agentService.updateTerm(agentId, id, term));
	}

	/** 删除术语(行 + 向量;向量清理触达模型,走弹性线程) */
	@DeleteMapping("/{agentId}/terms/{id}")
	public Mono<ApiResponse<Void>> deleteTerm(@PathVariable("agentId") long agentId, @PathVariable("id") long id) {
		return reactiveVoid(() -> agentService.deleteTerm(agentId, id));
	}

	/** 术语向量化重试:仅 PENDING / FAILED 行(模型调用走弹性线程) */
	@PostMapping("/{agentId}/terms/retry")
	public Mono<ApiResponse<Void>> retryTerms(@PathVariable("agentId") long agentId) {
		return reactiveVoid(() -> agentService.retryTerms(agentId));
	}

	// ============ 业务问答(bizqa) ============

	/** 问答清单 */
	@GetMapping("/{agentId}/qa")
	public ApiResponse<List<AgentBizQa>> listQa(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentService.listQa(agentId));
	}

	/** 新增问答(落库后立即同步问题向量;答案可后补;模型调用走弹性线程) */
	@PostMapping("/{agentId}/qa")
	public Mono<ApiResponse<AgentBizQa>> addQa(@PathVariable("agentId") long agentId, @RequestBody AgentBizQa qa) {
		return reactive(() -> agentService.addQa(agentId, qa));
	}

	/** 修改问答(仅问题变化才重同步;答案不入向量;模型调用走弹性线程) */
	@PostMapping("/{agentId}/qa/{id}")
	public Mono<ApiResponse<AgentBizQa>> updateQa(@PathVariable("agentId") long agentId, @PathVariable("id") long id,
			@RequestBody AgentBizQa qa) {
		return reactive(() -> agentService.updateQa(agentId, id, qa));
	}

	/** 删除问答(行 + 向量;向量清理触达模型,走弹性线程) */
	@DeleteMapping("/{agentId}/qa/{id}")
	public Mono<ApiResponse<Void>> deleteQa(@PathVariable("agentId") long agentId, @PathVariable("id") long id) {
		return reactiveVoid(() -> agentService.deleteQa(agentId, id));
	}

	/** 问答向量化重试:仅 PENDING / FAILED 行(模型调用走弹性线程) */
	@PostMapping("/{agentId}/qa/retry")
	public Mono<ApiResponse<Void>> retryQa(@PathVariable("agentId") long agentId) {
		return reactiveVoid(() -> agentService.retryQa(agentId));
	}

	// ============ 业务文档(bizdocument) ============

	/** 文档清单 */
	@GetMapping("/{agentId}/documents")
	public ApiResponse<List<AgentBizDocument>> listDocuments(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentService.listDocuments(agentId));
	}

	/** 上传文档(先落行、再落文件,落定后立即返回,后台异步切分向量化;name / splitterType 走查询参数,缺省取文件名 / PARAGRAPH) */
	@PostMapping(value = "/{agentId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ApiResponse<AgentBizDocument>> uploadDocument(@PathVariable("agentId") long agentId,
			@RequestPart("file") FilePart file, @RequestParam(name = "name", required = false) String name,
			@RequestParam(name = "splitterType", required = false) String splitterType) {
		return agentService
			.uploadDocument(agentId, file, name, splitterType == null ? null : SplitterType.from(splitterType))
			.map(ApiResponse::success);
	}

	/** 修改文档(改名 / 换切分策略;换策略自动重入向量) */
	@PostMapping("/{agentId}/documents/{id}")
	public ApiResponse<AgentBizDocument> updateDocument(@PathVariable("agentId") long agentId,
			@PathVariable("id") long id, @RequestBody AgentBizDocument document) {
		return ApiResponse.success(agentService.updateDocument(agentId, id, document));
	}

	/** 删除文档(行 + 向量 + 物理文件;向量清理触达模型,走弹性线程) */
	@DeleteMapping("/{agentId}/documents/{id}")
	public Mono<ApiResponse<Void>> deleteDocument(@PathVariable("agentId") long agentId, @PathVariable("id") long id) {
		return reactiveVoid(() -> agentService.deleteDocument(agentId, id));
	}

	/** 文档向量化重试:仅 PENDING / FAILED 行(模型调用走弹性线程) */
	@PostMapping("/{agentId}/documents/retry")
	public Mono<ApiResponse<Void>> retryDocuments(@PathVariable("agentId") long agentId) {
		return reactiveVoid(() -> agentService.retryDocuments(agentId));
	}

	/** 绑定请求体:业务库配置 + 表名清单 */
	public record BindTablesRequest(long databaseConfigId, List<String> tableNames) {
	}

}
