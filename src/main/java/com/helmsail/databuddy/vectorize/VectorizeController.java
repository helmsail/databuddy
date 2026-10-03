package com.helmsail.databuddy.vectorize;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.result.ApiResponse;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 向量域入口:目前仅向量运维动作(手动清理孤儿分区)。
 * 只做 HTTP 层,语义在 VectorService;清理含全库盘点与批量删除(阻塞),
 * 统一移入弹性线程(同 AgentController 的线程边界约定:WebFlux 事件循环上不可执行阻塞式 Spring AI 调用)
 */
@RestController
@RequestMapping("/vector")
@CrossOrigin(origins = "*")
public class VectorizeController {

	private final VectorService vectorService;

	public VectorizeController(VectorService vectorService) {
		this.vectorService = vectorService;
	}

	/** 手动清理孤儿分区(已无对应模型配置的残留向量);与定时任务共用同一条清理逻辑 */
	@PostMapping("/cleanup-orphans")
	public Mono<ApiResponse<Void>> cleanupOrphans() {
		return Mono.fromRunnable(vectorService::cleanupOrphanModels)
			.subscribeOn(Schedulers.boundedElastic())
			.thenReturn(ApiResponse.success());
	}

}
