package com.helmsail.databuddy.python;

import java.util.List;

/**
 * 沙箱执行结果:"执行已发生"的全部结局都在此(failure 细分失败类型,供 AI 决定后续行为);
 * "未能执行"的系统问题(借不到容器、池关闭、docker 不可用等)以异常形式抛出
 */
public record SandboxResult(String stdout, String stderr, int exitCode, List<OutputFile> files,
		SandboxFailure failure) {

	/** 产物文件:容器内 /work/output 下的文件(如 matplotlib 生成的图片) */
	public record OutputFile(String name, byte[] content) {
	}

	/** 是否带回任何产出(文本或文件);据此可判断"静默成功"(跑了但没产出) */
	public boolean hasOutput() {
		return (stdout != null && !stdout.isBlank()) || !files.isEmpty();
	}

}
