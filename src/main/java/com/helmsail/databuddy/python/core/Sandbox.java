package com.helmsail.databuddy.python.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.python.SandboxFailure;
import com.helmsail.databuddy.python.SandboxResult;

import lombok.extern.slf4j.Slf4j;

/**
 * 沙箱容器句柄(容器语义层):容器的创建、执行、健康检查、销毁行为尽在此,池只把它当"可借还的资源"。
 * 容器常驻(sleep infinity),每次执行用 docker exec 跑 /work/run.py;
 * 输入经 /work/input.json 提供,产物从 /work/output 收集,执行完即清工作目录(残留不跨次)。
 * docker 命令的"执行机制"(超时强杀/读流/防死锁)在 {@link DockerCli}
 */
@Slf4j
public class Sandbox {

	/** 创建容器超时 */
	private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(120);

	/** 健康检查超时 */
	private static final Duration PING_TIMEOUT = Duration.ofSeconds(10);

	/** 销毁容器超时 */
	private static final Duration DESTROY_TIMEOUT = Duration.ofSeconds(30);

	/** 执行后软重置超时(清 /tmp + 残留进程检测) */
	private static final Duration RESET_TIMEOUT = Duration.ofSeconds(15);

	/** docker top 超时 */
	private static final Duration TOP_TIMEOUT = Duration.ofSeconds(10);

	/** 干净容器的进程数(镜像 --init:tini + sleep infinity) */
	private static final int EXPECTED_PROCESS_COUNT = 2;

	/** 沙箱容器标签:启动时据此清理被强杀(未走 @PreDestroy)遗留的容器 */
	private static final String LABEL = "databuddy-sandbox";

	/** 启动清理残留资源超时 */
	private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(60);

	private final String name;

	private final Path workDir;

	private final String image;

	/** 最近一次使用时间(纳秒),供空闲驱逐判定 */
	private volatile long lastUsedNanos = System.nanoTime();

	/** 软重置发现残留(进程/清理失败),容器不再复用 */
	private volatile boolean dirty;

	public Sandbox(String name, Path workDir, String image) {
		this.name = name;
		this.workDir = workDir;
		this.image = image;
	}

	public String name() {
		return name;
	}

	public void markUsed() {
		lastUsedNanos = System.nanoTime();
	}

	public long lastUsedNanos() {
		return lastUsedNanos;
	}

	/** 是否已被标记为不可复用(执行后软重置发现残留或失败) */
	public boolean isDirty() {
		return dirty;
	}

	/**
	 * 创建常驻容器:全部安全限制固化在命令行(属于安全基线,不做成配置)。
	 * --init 让 tini 当 PID 1(自动收割僵尸进程);--pull=never 防运行期拉镜像(镜像必须提前构建好)
	 */
	public void create() {
		try {
			Files.createDirectories(workDir);
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建沙箱工作目录失败: " + e.getMessage(), e);
		}
		relaxPermissions(workDir); // 容器以 uid 1000 运行,需可写此目录(容器化/Linux 部署必需;非 POSIX 文件系统跳过)
		DockerCli.ProcessResult result = DockerCli.runProcess(List.of(
				"docker", "run", "-d", "--name", name, "--pull=never", "--init",
				"--label", LABEL, // 便于启动时清理强杀残留
				"--network", "none", // 断网
				"--read-only", // 根文件系统只读
				"--tmpfs", "/tmp:size=64m", // 唯一可写的临时区(64m 上限,防蚕食宿主内存)
				"--memory", "512m", "--cpus", "1", "--pids-limit", "64",
				"--user", "1000", // 非 root
				"--cap-drop", "ALL", "--security-opt", "no-new-privileges",
				"-v", workDir.toAbsolutePath().normalize() + ":/work",
				image, "sleep", "infinity"),
				CREATE_TIMEOUT);
		if (result.timedOut()) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建沙箱容器超时");
		}
		if (result.exitCode() != 0) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建沙箱容器失败: " + result.stderr());
		}
	}

	/** 放开工作目录权限(rwxrwxrwx):沙箱容器以 uid 1000 运行,需能写入(含运行期自建 output 目录);
	 * Windows/挂载文件系统等非 POSIX 场景静默跳过(chmod 无效但写入本就放行) */
	private static void relaxPermissions(Path dir) {
		try {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
		}
		catch (UnsupportedOperationException | IOException e) {
			log.debug("跳过工作目录权限放开(非 POSIX 文件系统): {}", e.getMessage());
		}
	}

	/** 执行代码:准备文件 → docker exec → 收集产物 → 清工作目录 */
	public SandboxResult exec(String code, String inputJson, Duration timeout) {
		try {
			clearWorkDir();
			Files.writeString(workDir.resolve("run.py"), code, StandardCharsets.UTF_8);
			if (inputJson != null) {
				Files.writeString(workDir.resolve("input.json"), inputJson, StandardCharsets.UTF_8);
			}
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "准备沙箱执行文件失败: " + e.getMessage(), e);
		}
		try {
			DockerCli.ProcessResult result = DockerCli.runProcess(
					List.of("docker", "exec", name, "python", "/work/run.py"), timeout);
			SandboxResult sandboxResult = buildResult(result);
			if (!dirty) {
				softReset(); // 已判脏(超时/环境错误)的容器将直接销毁,无需再清理
			}
			return sandboxResult;
		}
		finally {
			try {
				clearWorkDir();
			}
			catch (IOException e) {
				log.warn("清理沙箱工作目录失败: {}", e.getMessage());
			}
		}
	}

	/**
	 * 依据 docker 命令结果构建执行结果:超时/环境层错误属"执行已发生"的失败,走结果返回,
	 * 但容器状态不可信,连带标记 dirty(由池销毁重建);代码错误(含 OOM 被杀)容器健康可复用
	 */
	private SandboxResult buildResult(DockerCli.ProcessResult result) {
		if (result.timedOut()) {
			dirty = true;
			log.warn("沙箱容器 {} 执行超时,容器将销毁", name);
			return new SandboxResult(result.stdout(), result.stderr(), -1, List.of(), SandboxFailure.TIMEOUT);
		}
		SandboxFailure failure = SandboxFailure.classify(result.exitCode(), result.stderr());
		if (failure == SandboxFailure.ENV_ERROR) {
			dirty = true;
			log.warn("沙箱容器 {} 出现环境层错误,容器将销毁", name);
		}
		return new SandboxResult(result.stdout(), result.stderr(), result.exitCode(), readOutputFiles(), failure);
	}

	/** 健康检查:容器处于 running 视为健康 */
	public boolean ping() {
		try {
			DockerCli.ProcessResult result = DockerCli.runProcess(
					List.of("docker", "inspect", "-f", "{{.State.Running}}", name), PING_TIMEOUT);
			return !result.timedOut() && result.exitCode() == 0 && "true".equals(result.stdout().trim());
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * 执行后软重置(容器复用合法性的前提):清 /tmp 防文件残留;docker top(cgroup 视角,零容器内依赖)
	 * 检测残留进程防孤儿进程。发现任何残留或清理异常,标记 dirty,由池销毁重建
	 */
	private void softReset() {
		try {
			DockerCli.ProcessResult clean = DockerCli.runProcess(List.of("docker", "exec", name, "sh", "-c",
					"rm -rf /tmp/* /tmp/.[!.]* /tmp/..?* 2>/dev/null || true"), RESET_TIMEOUT);
			DockerCli.ProcessResult top = DockerCli.runProcess(List.of("docker", "top", name), TOP_TIMEOUT);
			long processCount = top.stdout().lines().filter(line -> !line.isBlank()).count() - 1; // 减表头
			boolean cleanFailed = clean.timedOut() || clean.exitCode() != 0;
			boolean hasResidual = top.timedOut() || top.exitCode() != 0 || processCount > EXPECTED_PROCESS_COUNT;
			if (cleanFailed || hasResidual) {
				log.warn("沙箱容器 {} 软重置未通过(清理失败={} 残留进程={} 个),不再复用", name, cleanFailed, processCount);
				dirty = true;
			}
		}
		catch (RuntimeException e) {
			dirty = true;
			log.warn("沙箱容器 {} 软重置失败,不再复用: {}", name, e.getMessage());
		}
	}

	/** 销毁容器与工作目录(幂等,清理失败仅告警) */
	public void destroy() {
		try {
			DockerCli.runProcess(List.of("docker", "rm", "-f", name), DESTROY_TIMEOUT);
		}
		catch (RuntimeException e) {
			log.warn("销毁沙箱容器 {} 失败: {}", name, e.getMessage());
		}
		try {
			deleteRecursively(workDir);
		}
		catch (IOException e) {
			log.warn("清理沙箱工作目录 {} 失败: {}", workDir, e.getMessage());
		}
	}

	/** 启动时清理:上一次进程被强杀(未走 @PreDestroy)遗留的沙箱容器与工作目录 */
	public static void cleanupLeftovers(String workRoot) {
		try {
			DockerCli.ProcessResult list = DockerCli.runProcess(
					List.of("docker", "ps", "-aq", "--filter", "label=" + LABEL), CLEANUP_TIMEOUT);
			if (list.timedOut() || list.exitCode() != 0) {
				log.warn("检索残留沙箱容器失败,跳过容器清理: {}", list.stderr());
			}
			else {
				List<String> ids = list.stdout().lines().map(String::trim).filter(id -> !id.isEmpty()).toList();
				if (!ids.isEmpty()) {
					List<String> command = new ArrayList<>();
					command.add("docker");
					command.add("rm");
					command.add("-f");
					command.addAll(ids);
					DockerCli.runProcess(command, CLEANUP_TIMEOUT);
					log.info("已清理残留沙箱容器 {} 个", ids.size());
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("启动清理残留沙箱容器失败(忽略): {}", e.getMessage());
		}
		try {
			deleteRecursively(Path.of(workRoot));
		}
		catch (IOException e) {
			log.warn("启动清理沙箱工作目录失败(忽略): {}", e.getMessage());
		}
	}

	/** 递归删除目录及其内容(不存在则忽略) */
	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	/** 清空工作目录内容(保留目录本身) */
	private void clearWorkDir() throws IOException {
		if (!Files.exists(workDir)) {
			Files.createDirectories(workDir);
			return;
		}
		try (Stream<Path> paths = Files.walk(workDir)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				if (!path.equals(workDir)) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	/** 收集容器产物:工作目录下 output/ 的全部文件 */
	private List<SandboxResult.OutputFile> readOutputFiles() {
		Path outputDir = workDir.resolve("output");
		if (!Files.isDirectory(outputDir)) {
			return List.of();
		}
		try (Stream<Path> paths = Files.walk(outputDir)) {
			return paths.filter(Files::isRegularFile)
				.map(path -> new SandboxResult.OutputFile(
						outputDir.relativize(path).toString().replace('\\', '/'), readBytes(path)))
				.toList();
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取沙箱产物失败: " + e.getMessage(), e);
		}
	}

	private byte[] readBytes(Path path) {
		try {
			return Files.readAllBytes(path);
		}
		catch (IOException e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取沙箱产物失败: " + e.getMessage(), e);
		}
	}

}
