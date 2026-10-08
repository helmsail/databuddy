package com.helmsail.databuddy.middle.graph.python;

/**
 * Python 域常量(唯一来源):节点 ID 与组重试上限
 */
public final class PythonConstants {

	/** Python 生成节点(Python 组头) */
	public static final String PYTHON_GENERATE = "python-generate";

	/** Python 执行节点(沙箱运行) */
	public static final String PYTHON_EXECUTE = "python-execute";

	/** Python 分析节点(Python 组质检,执行后审结果) */
	public static final String PYTHON_ANALYZE = "python-analyze";

	/** Python 组重试上限(执行失败重生成计数;超限升级重规划) */
	public static final int PYTHON_RETRY_MAX = 3;

	private PythonConstants() {
	}

}
