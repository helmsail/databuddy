package com.helmsail.databuddy.python;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.python.core.Sandbox;
import com.helmsail.databuddy.python.core.SandboxPool;

import jakarta.annotation.PreDestroy;

/**
 * python 沙箱工厂:业务唯一入口 —— 借容器、执行、归还全部封装在此,调用方拿不到容器句柄。
 * 注意:execute 是阻塞方法(等待容器 + 等待执行完成),
 * WebFlux 消费方需在 boundedElastic 等弹性调度器上调用
 */
@Component
public class PythonSandboxFactory {

	private final SandboxProperties properties;

	private final SandboxPool pool;

	public PythonSandboxFactory(SandboxProperties properties) {
		this.properties = properties;
		this.pool = new SandboxPool(properties);
		this.pool.start();
	}

	/** 执行 python 代码:data 以 /work/input.json 提供给代码,产物读取自 /work/output */
	public SandboxResult execute(String code, String inputJson) {
		Sandbox sandbox = pool.borrow(properties.getBorrowTimeout());
		boolean healthy = true;
		try {
			return sandbox.exec(code, inputJson, properties.getExecTimeout());
		}
		catch (RuntimeException e) {
			// 未能执行的系统异常(进程无法启动/IO 失败等)= 容器状态不可信,不归还,直接销毁
			healthy = false;
			throw e;
		}
		finally {
			// 软重置发现残留(孤儿进程等)也视为不可复用,销毁重建
			pool.release(sandbox, healthy && !sandbox.isDirty());
		}
	}

	@PreDestroy
	public void close() {
		pool.shutdown();
	}

}
