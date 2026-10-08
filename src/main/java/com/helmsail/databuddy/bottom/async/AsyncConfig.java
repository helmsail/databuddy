package com.helmsail.databuddy.bottom.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 异步执行器装配:全项目"自管线程池"的集中声明——命名线程、容器管生命周期、指标自动接入(Micrometer)。
 * 请求内阻塞段的弹性池调度(控制器出口 / 图订阅处)继续用 Reactor boundedElastic,不入本包
 */
@Configuration
public class AsyncConfig {

	/** 向量化后台队列:单线程 FIFO 串行(读文件 + 批量嵌入是重 IO,按序处理即可);停机丢队列,遗留行由下次启动定时兜底 */
	@Bean
	public ThreadPoolTaskExecutor vectorSyncExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setThreadNamePrefix("vector-sync-");
		executor.initialize();
		return executor;
	}

	/** 定时任务调度器:四域兜底 Job 共用(独立命名线程;同一任务 fixedDelay 不重入,不同任务可并行) */
	@Bean
	public TaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("sched-");
		scheduler.initialize();
		return scheduler;
	}

}
