package com.helmsail.databuddy.middle.python;

import java.util.List;

/**
 * 沙箱执行结果:"执行已发生"的全部结局都在此(type 兼含成功与三类失败,分类以"后续修复动作"为准:
 * 不用修 / 改代码 / 换容器 / 时间不够);"未能执行"的系统问题(借不到容器、池关闭等)以异常形式抛出
 */
public record SandboxResult(String stdout, String stderr, int exitCode, List<OutputFile> files, Type type) {

	/** 结局类型(成功与失败的统一分类,消费方按它决定后续动作) */
	public enum Type {

		/** 正常完成(exitCode = 0)——交付给分析/报告 */
		SUCCESS,

		/** 代码问题:python 异常、信号崩溃、内存超限被杀(137)——交给 AI 修代码 */
		CODE_ERROR,

		/** 环境问题:容器中途死亡、docker 层错误——系统重试/换容器,不调 AI(非确定性故障) */
		ENV_ERROR,

		/** 执行超时被强杀——重试/提示超时/让 AI 优化 */
		TIMEOUT;

		/** docker 层错误的 stderr 特征(区别于 python traceback) */
		private static final String[] DOCKER_ERROR_MARKERS = { "Error response from daemon",
				"Cannot connect to the Docker daemon", "error during connect", "No such container" };

		/** 按退出码与 stderr 判定(超时由调用方先行判定) */
		public static Type classify(int exitCode, String stderr) {
			if (exitCode == 0) {
				return SUCCESS;
			}
			if (stderr != null) {
				for (String marker : DOCKER_ERROR_MARKERS) {
					if (stderr.contains(marker)) {
						return ENV_ERROR;
					}
				}
			}
			return CODE_ERROR;
		}
	}

	/** 产物文件:容器内 /work/output 下的文件(如 matplotlib 生成的图片) */
	public record OutputFile(String name, byte[] content) {
	}

	/** 是否带回任何产出(文本或文件);据此可判断"静默成功"(跑了但没产出) */
	public boolean hasOutput() {
		return (stdout != null && !stdout.isBlank()) || !files.isEmpty();
	}

}
