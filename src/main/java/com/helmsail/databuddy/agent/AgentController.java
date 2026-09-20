package com.helmsail.databuddy.agent;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
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
import com.helmsail.databuddy.agent.bizdocument.AgentBizDocumentService;
import com.helmsail.databuddy.agent.bizqa.AgentBizQa;
import com.helmsail.databuddy.agent.bizqa.AgentBizQaService;
import com.helmsail.databuddy.agent.biztable.AgentBizTable;
import com.helmsail.databuddy.agent.biztable.AgentBizTableService;
import com.helmsail.databuddy.agent.bizterm.AgentBizTerm;
import com.helmsail.databuddy.agent.bizterm.AgentBizTermService;
import com.helmsail.databuddy.result.ApiResponse;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 智能体入口:agent 本体与检索走 AgentService,四子域管理动作直调各子域 service(域内允许);
 * 只做 HTTP 层,语义全在 service;成功返回统一信封(ApiResponse),错误由全局异常处理器转同形信封
 */
@RestController
@RequestMapping("/agent")
@CrossOrigin(origins = "*")
public class AgentController {

	private final AgentService agentService;

	private final AgentBizTableService agentBizTableService;

	private final AgentBizTermService agentBizTermService;

	private final AgentBizQaService agentBizQaService;

	private final AgentBizDocumentService agentBizDocumentService;

	public AgentController(AgentService agentService, AgentBizTableService agentBizTableService,
			AgentBizTermService agentBizTermService, AgentBizQaService agentBizQaService,
			AgentBizDocumentService agentBizDocumentService) {
		this.agentService = agentService;
		this.agentBizTableService = agentBizTableService;
		this.agentBizTermService = agentBizTermService;
		this.agentBizQaService = agentBizQaService;
		this.agentBizDocumentService = agentBizDocumentService;
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

	/** 删除 agent(级联:四类知识源的行 / 向量 / 物理文件) */
	@DeleteMapping("/{agentId}")
	public ApiResponse<Void> delete(@PathVariable("agentId") long agentId) {
		agentService.delete(agentId);
		return ApiResponse.success();
	}

	/** 检索联调口(节点侧走 AgentService.retrieve;返回结构化块,含回源字段,不做上下文成文) */
	@GetMapping("/{agentId}/retrieve")
	public ApiResponse<List<RetrievedChunk>> retrieve(@PathVariable("agentId") long agentId,
			@RequestParam("query") String query, @RequestParam(name = "topK", defaultValue = "5") int topK) {
		return ApiResponse.success(agentService.retrieve(agentId, query, topK));
	}

	// ============ 表绑定(biztable) ============

	/** 表绑定清单 */
	@GetMapping("/{agentId}/biz-tables")
	public ApiResponse<List<AgentBizTable>> listTables(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentBizTableService.list(agentId));
	}

	/** 绑定业务表(校验库与表存在;已绑定幂等跳过;新增行 PENDING,再触发同步) */
	@PostMapping("/{agentId}/biz-tables")
	public ApiResponse<Void> bindTables(@PathVariable("agentId") long agentId, @RequestBody BindTablesRequest request) {
		agentBizTableService.bind(agentId, request.databaseConfigId(), request.tableNames());
		return ApiResponse.success();
	}

	/** 解绑(删行 + 删向量;id 不属于该 agent 的静默跳过) */
	@DeleteMapping("/{agentId}/biz-tables")
	public ApiResponse<Void> unbindTables(@PathVariable("agentId") long agentId, @RequestBody List<Long> ids) {
		agentBizTableService.unbind(agentId, ids);
		return ApiResponse.success();
	}

	/** 表向量化:全量重建(绑定后首刷 / 模型切换后重刷) */
	@PostMapping("/{agentId}/biz-tables/sync")
	public ApiResponse<Void> syncTables(@PathVariable("agentId") long agentId) {
		agentBizTableService.sync(agentId);
		return ApiResponse.success();
	}

	/** 表向量化重试:仅 PENDING / FAILED 行(手动与定时共用入口) */
	@PostMapping("/{agentId}/biz-tables/retry")
	public ApiResponse<Void> retryTables(@PathVariable("agentId") long agentId) {
		agentBizTableService.retryUnsynced(agentId);
		return ApiResponse.success();
	}

	// ============ 业务术语(bizterm) ============

	/** 术语清单 */
	@GetMapping("/{agentId}/terms")
	public ApiResponse<List<AgentBizTerm>> listTerms(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentBizTermService.list(agentId));
	}

	/** 新增术语(落库后立即同步向量) */
	@PostMapping("/{agentId}/terms")
	public ApiResponse<AgentBizTerm> addTerm(@PathVariable("agentId") long agentId, @RequestBody AgentBizTerm term) {
		return ApiResponse.success(agentBizTermService.add(agentId, term));
	}

	/** 修改术语(术语 / 同义词 / 释义,立即重同步) */
	@PostMapping("/{agentId}/terms/{id}")
	public ApiResponse<AgentBizTerm> updateTerm(@PathVariable("agentId") long agentId, @PathVariable("id") long id,
			@RequestBody AgentBizTerm term) {
		return ApiResponse.success(agentBizTermService.update(id, term));
	}

	/** 删除术语(行 + 向量) */
	@DeleteMapping("/{agentId}/terms/{id}")
	public ApiResponse<Void> deleteTerm(@PathVariable("agentId") long agentId, @PathVariable("id") long id) {
		agentBizTermService.delete(id);
		return ApiResponse.success();
	}

	/** 术语向量化重试:仅 PENDING / FAILED 行 */
	@PostMapping("/{agentId}/terms/retry")
	public ApiResponse<Void> retryTerms(@PathVariable("agentId") long agentId) {
		agentBizTermService.retryUnsynced(agentId);
		return ApiResponse.success();
	}

	// ============ 业务问答(bizqa) ============

	/** 问答清单 */
	@GetMapping("/{agentId}/qa")
	public ApiResponse<List<AgentBizQa>> listQa(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentBizQaService.list(agentId));
	}

	/** 新增问答(落库后立即同步问题向量;答案可后补) */
	@PostMapping("/{agentId}/qa")
	public ApiResponse<AgentBizQa> addQa(@PathVariable("agentId") long agentId, @RequestBody AgentBizQa qa) {
		return ApiResponse.success(agentBizQaService.add(agentId, qa));
	}

	/** 修改问答(仅问题变化才重同步;答案不入向量) */
	@PostMapping("/{agentId}/qa/{id}")
	public ApiResponse<AgentBizQa> updateQa(@PathVariable("agentId") long agentId, @PathVariable("id") long id,
			@RequestBody AgentBizQa qa) {
		return ApiResponse.success(agentBizQaService.update(id, qa));
	}

	/** 删除问答(行 + 向量) */
	@DeleteMapping("/{agentId}/qa/{id}")
	public ApiResponse<Void> deleteQa(@PathVariable("agentId") long agentId, @PathVariable("id") long id) {
		agentBizQaService.delete(id);
		return ApiResponse.success();
	}

	/** 问答向量化重试:仅 PENDING / FAILED 行 */
	@PostMapping("/{agentId}/qa/retry")
	public ApiResponse<Void> retryQa(@PathVariable("agentId") long agentId) {
		agentBizQaService.retryUnsynced(agentId);
		return ApiResponse.success();
	}

	// ============ 业务文档(bizdocument) ============

	/** 文档清单 */
	@GetMapping("/{agentId}/documents")
	public ApiResponse<List<AgentBizDocument>> listDocuments(@PathVariable("agentId") long agentId) {
		return ApiResponse.success(agentBizDocumentService.list(agentId));
	}

	/** 上传文档(文件落存储后立即返回,后台异步切分向量化;name / splitterType 走查询参数,缺省取文件名 / PARAGRAPH) */
	@PostMapping(value = "/{agentId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<ApiResponse<AgentBizDocument>> uploadDocument(@PathVariable("agentId") long agentId,
			@RequestPart("file") FilePart file, @RequestParam(name = "name", required = false) String name,
			@RequestParam(name = "splitterType", required = false) String splitterType) {
		return agentBizDocumentService
			.upload(agentId, file, name, splitterType == null ? null : SplitterType.from(splitterType))
			.map(ApiResponse::success);
	}

	/** 下载文档(文件本体;附件流响应,文件名取文档名,中文不乱码) */
	@GetMapping("/{agentId}/documents/{id}/download")
	public Mono<ResponseEntity<Resource>> downloadDocument(@PathVariable("agentId") long agentId,
			@PathVariable("id") long id) {
		return Mono.fromCallable(() -> {
			AgentBizDocumentService.DocumentFile file = agentBizDocumentService.download(id);
			return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
					.filename(file.name(), StandardCharsets.UTF_8)
					.build()
					.toString())
				.body(file.resource());
		})
			.subscribeOn(Schedulers.boundedElastic());
	}

	/** 修改文档(改名 / 换切分策略;换策略自动重入向量) */
	@PostMapping("/{agentId}/documents/{id}")
	public ApiResponse<AgentBizDocument> updateDocument(@PathVariable("agentId") long agentId,
			@PathVariable("id") long id, @RequestBody AgentBizDocument document) {
		return ApiResponse.success(agentBizDocumentService.update(id, document));
	}

	/** 删除文档(行 + 向量 + 物理文件) */
	@DeleteMapping("/{agentId}/documents/{id}")
	public ApiResponse<Void> deleteDocument(@PathVariable("agentId") long agentId, @PathVariable("id") long id) {
		agentBizDocumentService.delete(id);
		return ApiResponse.success();
	}

	/** 文档向量化重试:仅 PENDING / FAILED 行 */
	@PostMapping("/{agentId}/documents/retry")
	public ApiResponse<Void> retryDocuments(@PathVariable("agentId") long agentId) {
		agentBizDocumentService.retryUnsynced(agentId);
		return ApiResponse.success();
	}

	/** 绑定请求体:业务库配置 + 表名清单 */
	public record BindTablesRequest(long databaseConfigId, List<String> tableNames) {
	}

}
