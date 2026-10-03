package com.helmsail.databuddy.vectorize;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

/**
 * 孤儿向量分区清理(定时):回收模型配置已删除的残留向量分区(见 VectorService.cleanupOrphanModels)。
 * 只做触发,业务逻辑在 VectorService;长周期低频(默认启动后 5 分钟首跑,之后每 6 小时一轮;
 * 经 databuddy.vector-cleanup.* 可覆盖)。清理含全库盘点与批量删除、耗时不可控:
 * 甩弹性线程异步执行不占调度线程;AtomicBoolean 防重入(异步后 fixedDelay 不再天然串行)
 */
@Slf4j
@Component
public class VectorModelCleanupJob {

	private final VectorService vectorService;

	private final AtomicBoolean running = new AtomicBoolean();

	public VectorModelCleanupJob(VectorService vectorService) {
		this.vectorService = vectorService;
	}

	/** 定时触发孤儿分区清理(上轮未结束则跳过本轮) */
	@Scheduled(initialDelayString = "${databuddy.vector-cleanup.initial-delay-ms:300000}",
			fixedDelayString = "${databuddy.vector-cleanup.interval-ms:21600000}")
	public void cleanupOrphanModels() {
		if (!running.compareAndSet(false, true)) {
			log.info("上一轮孤儿分区清理仍在执行,跳过本轮触发");
			return;
		}
		Schedulers.boundedElastic().schedule(() -> {
			try {
				int cleaned = vectorService.cleanupOrphanModels();
				if (cleaned > 0) {
					log.info("孤儿分区清理完成: 清理 {} 个分区", cleaned);
				}
			}
			catch (Exception e) {
				log.error("孤儿分区清理失败(下轮重新扫描,幂等)", e);
			}
			finally {
				running.set(false);
			}
		});
	}

}
