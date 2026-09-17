package com.helmsail.databuddy.python.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

/**
 * docker 命令执行机制(机制层):只负责"怎么跑命令"——拉起进程、并发读流、超时强杀;
 * 不关心"跑什么"(命令语义由调用方决定)。
 * 输出封顶 MAX_OUTPUT_BYTES:超限部分继续读并丢弃(防管道写满阻塞进程),尾部标注已截断
 */
class DockerCli {

	/** 捕获输出的字节上限(stdout/stderr 共用),超出部分丢弃并标注 */
	private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

	private DockerCli() {
	}

	/** 执行 docker 命令:并发消费 stdout/stderr 防管道死锁;超时强杀并返回 timedOut(由调用方决定语义) */
	static ProcessResult runProcess(List<String> command, Duration timeout) {
		Process process;
		try {
			process = new ProcessBuilder(command).start();
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动 docker 命令失败: " + e.getMessage(), e);
		}
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		Thread stdoutReader = startReader(process.getInputStream(), stdout);
		Thread stderrReader = startReader(process.getErrorStream(), stderr);
		try {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				joinQuietly(stdoutReader);
				joinQuietly(stderrReader);
				// -1:被强杀无正常退出码;携带已捕获的部分输出
				return new ProcessResult(-1, stdout.toString(StandardCharsets.UTF_8),
						stderr.toString(StandardCharsets.UTF_8), true);
			}
			stdoutReader.join();
			stderrReader.join();
		}
		catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "等待 docker 命令被中断", e);
		}
		return new ProcessResult(process.exitValue(), stdout.toString(StandardCharsets.UTF_8),
				stderr.toString(StandardCharsets.UTF_8), false);
	}

	private static void joinQuietly(Thread thread) {
		try {
			thread.join(1000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 读取流到内存:封顶 MAX_OUTPUT_BYTES 防大输出打爆宿主;超限部分继续读并丢弃(防管道写满阻塞进程) */
	private static Thread startReader(InputStream in, ByteArrayOutputStream out) {
		Thread thread = new Thread(() -> {
			byte[] buffer = new byte[8192];
			boolean truncated = false;
			try {
				int length;
				while ((length = in.read(buffer)) != -1) {
					int remaining = MAX_OUTPUT_BYTES - out.size();
					if (remaining > 0) {
						out.write(buffer, 0, Math.min(length, remaining));
					}
					else {
						truncated = true;
					}
				}
				if (truncated) {
					out.write("\n...[输出超过 1MB,已截断]".getBytes(StandardCharsets.UTF_8));
				}
			}
			catch (IOException ignored) {
				// 进程被强杀时读线程随之结束
			}
		});
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/** docker 命令执行结果(内部工具);timedOut=true 表示被外部强杀,exitCode 为 -1 */
	record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut) {
	}

}
