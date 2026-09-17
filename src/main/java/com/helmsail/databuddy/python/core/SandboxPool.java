package com.helmsail.databuddy.python.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.python.SandboxProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 沙箱容器池:连接池语义(borrow/release),参照 Druid 骨架:
 * 锁 + 两个条件(notEmpty 供借者等待、empty 供 creator 等待需求)
 * - Creator 线程:有等待者(total 未达上限)或不足常驻数时创建容器,按需补充
 * - Evictor 线程:空闲超过 TTL 且总数大于常驻数的容器销毁(收缩)
 * 借出的容器由其使用方 release:健康归还入池,不健康销毁并让出名额
 */
@Slf4j
public class SandboxPool {

	/** 空闲驱逐巡检周期(秒) */
	private static final long EVICT_INTERVAL_SECONDS = 60;

	/** 创建失败后的重试退避(毫秒) */
	private static final long CREATE_RETRY_BACKOFF_MILLIS = 1000L;

	private final SandboxProperties properties;

	private final ReentrantLock lock = new ReentrantLock();

	/** 借者等待"有容器可用" */
	private final Condition notEmpty = lock.newCondition();

	/** creator 等待"有创建需求" */
	private final Condition empty = lock.newCondition();

	private final Deque<Sandbox> idle = new ArrayDeque<>();

	/** 容器总数(空闲 + 借出 + 创建中) */
	private int total;

	/** 等待者数(creator 按需创建的判据) */
	private int waitCount;

	private volatile boolean closed;

	private Thread creator;

	private ScheduledExecutorService evictor;

	public SandboxPool(SandboxProperties properties) {
		this.properties = properties;
	}

	/** 启动 creator(异步预热 + 按需创建)与 evictor(TTL 收缩) */
	public void start() {
		Sandbox.cleanupLeftovers(properties.getWorkRoot()); // 先清强杀残留,再启动 creator(避免误清新容器)
		creator = new Thread(this::creatorLoop, "python-sandbox-creator");
		creator.setDaemon(true);
		creator.start();

		evictor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "python-sandbox-evictor");
			thread.setDaemon(true);
			return thread;
		});
		evictor.scheduleWithFixedDelay(this::evict, EVICT_INTERVAL_SECONDS, EVICT_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	/** 借容器:有空闲直接借出(借前健康检查),空则等待 creator 创建,超时视为容量耗尽 */
	public Sandbox borrow(Duration timeout) {
		long remaining = timeout.toNanos();
		while (true) {
			Sandbox sandbox = null;
			lock.lock();
			try {
				while (sandbox == null) {
					if (closed) {
						throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱池已关闭");
					}
					sandbox = idle.pollFirst();
					if (sandbox != null) {
						break;
					}
					if (remaining <= 0) {
						throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱容量耗尽,请稍后重试");
					}
					waitCount++;
					empty.signal(); // 唤醒 creator 按需创建
					try {
						remaining = notEmpty.awaitNanos(remaining);
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new BusinessException(ErrorCode.SYSTEM_ERROR, "等待沙箱容器被中断", e);
					}
					finally {
						waitCount--;
					}
				}
			}
			finally {
				lock.unlock();
			}

			// 借前健康检查(锁外执行):容器已死则销毁、让出名额,重新借
			if (sandbox.ping()) {
				sandbox.markUsed();
				return sandbox;
			}
			log.warn("沙箱容器 {} 健康检查失败,销毁重建", sandbox.name());
			lock.lock();
			try {
				total--;
				if (waitCount > 0 || total < properties.getMinIdle()) {
					empty.signal();
				}
			}
			finally {
				lock.unlock();
			}
			sandbox.destroy();
		}
	}

	/** 归还容器:健康则入池并唤醒借者;不健康则销毁并让出名额 */
	public void release(Sandbox sandbox, boolean healthy) {
		if (healthy) {
			lock.lock();
			try {
				if (!closed) {
					sandbox.markUsed();
					idle.addFirst(sandbox);
					notEmpty.signal();
					return;
				}
			}
			finally {
				lock.unlock();
			}
		}
		// 不健康或池已关闭:记名额、不足常驻或有等待者时补新容器,再销毁
		lock.lock();
		try {
			total--;
			if (waitCount > 0 || total < properties.getMinIdle()) {
				empty.signal();
			}
		}
		finally {
			lock.unlock();
		}
		sandbox.destroy();
	}

	/** 关闭池:停止两条线程并销毁全部空闲容器(借出中的在归还时销毁) */
	public void shutdown() {
		closed = true;
		lock.lock();
		try {
			notEmpty.signalAll();
			empty.signalAll();
		}
		finally {
			lock.unlock();
		}
		if (creator != null) {
			creator.interrupt();
		}
		if (evictor != null) {
			evictor.shutdownNow();
		}
		List<Sandbox> remaining = new ArrayList<>();
		lock.lock();
		try {
			remaining.addAll(idle);
			idle.clear();
			total -= remaining.size();
		}
		finally {
			lock.unlock();
		}
		remaining.forEach(Sandbox::destroy);
		log.info("沙箱池已关闭,销毁空闲容器 {} 个", remaining.size());
	}

	/** creator:按需创建(有等待者且未达上限,或不足常驻数) */
	private void creatorLoop() {
		while (!closed) {
			lock.lock();
			try {
				while (!closed && !needCreate()) {
					try {
						empty.await();
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				if (closed) {
					return;
				}
				total++; // 先占名额(创建中)
			}
			finally {
				lock.unlock();
			}

			Sandbox sandbox = null;
			try {
				sandbox = createSandbox();
			}
			catch (RuntimeException e) {
				log.error("创建沙箱容器失败: {}", e.getMessage());
			}

			lock.lock();
			try {
				if (sandbox != null && !closed) {
					idle.addFirst(sandbox);
					notEmpty.signal();
				}
				else {
					total--; // 创建失败或池已关闭:让出名额
				}
			}
			finally {
				lock.unlock();
			}

			if (sandbox != null && closed) {
				sandbox.destroy();
			}
			if (sandbox == null) {
				sleepQuietly(CREATE_RETRY_BACKOFF_MILLIS);
			}
		}
	}

	/** 创建判据:未达上限,且(有等待者 或 不足常驻数);调用方需持锁 */
	private boolean needCreate() {
		return total < properties.getMaxTotal() && (waitCount > 0 || total < properties.getMinIdle());
	}

	private Sandbox createSandbox() {
		String name = "databuddy-python-" + UUID.randomUUID().toString().substring(0, 8);
		Sandbox sandbox = new Sandbox(name, Path.of(properties.getWorkRoot(), name), properties.getImage());
		sandbox.create();
		log.info("沙箱容器 {} 创建完成", name);
		return sandbox;
	}

	/** evictor:收缩空闲超 TTL 的容器(从最久未用的一端开始,收缩到常驻数为止) */
	private void evict() {
		List<Sandbox> expired = new ArrayList<>();
		lock.lock();
		try {
			if (waitCount > 0) {
				return; // 有等待者时不收缩(需求旺盛,收缩只会马上再建)
			}
			long now = System.nanoTime();
			long ttlNanos = properties.getIdleTtl().toNanos();
			Iterator<Sandbox> iterator = idle.descendingIterator(); // 队尾 = 最久未用
			while (iterator.hasNext()) {
				if (total <= properties.getMinIdle()) {
					break;
				}
				Sandbox sandbox = iterator.next();
				if (now - sandbox.lastUsedNanos() < ttlNanos) {
					break;
				}
				iterator.remove();
				total--;
				expired.add(sandbox);
			}
		}
		finally {
			lock.unlock();
		}
		for (Sandbox sandbox : expired) {
			log.info("收缩空闲沙箱容器 {}", sandbox.name());
			sandbox.destroy();
		}
	}

	private void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
