package com.helmsail.databuddy.python;

/**
 * 执行失败的分类(仅覆盖"执行已发生"的情形;未能执行走异常)。
 * 分类以"后续修复动作"为准:改代码 / 重试就行 / 时间不够
 */
public enum SandboxFailure {

	/** 正常完成(exitCode = 0) */
	NONE,

	/** 代码问题:python 异常、信号崩溃、内存超限被杀(137)——交给 LLM 修代码 */
	CODE_ERROR,

	/** 环境问题:容器中途死亡、docker 层错误——系统重试/换容器,不调 AI(非确定性故障) */
	ENV_ERROR,

	/** 执行超时被强杀——重试/提示超时/让 AI 优化 */
	TIMEOUT;

	/** docker 层错误的 stderr 特征(区别于 python traceback) */
	private static final String[] DOCKER_ERROR_MARKERS = { "Error response from daemon",
			"Cannot connect to the Docker daemon", "error during connect", "No such container" };

	/** 按退出码与 stderr 判定失败类型(超时由调用方先行判定) */
	public static SandboxFailure classify(int exitCode, String stderr) {
		if (exitCode == 0) {
			return NONE;
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
