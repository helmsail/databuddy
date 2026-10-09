package com.helmsail.databuddy.middle.graph.python;

/**
 * Python 域常量(唯一来源):节点 ID 与组重试上限
 */
public final class PythonConstants {

	/** Python 生成节点(Python 组头) */
	public static final String PYTHON_GENERATE = "python-generate";

	/** Python 执行节点(沙箱运行) */
	public static final String PYTHON_EXECUTE = "python-execute";

	/** Python 分析节点(Python 组闸,执行后审数据) */
	public static final String PYTHON_ANALYZE = "python-analyze";

	/** Python 组重试上限(生成即计数;执行/分析失败均打回生成,超限在生成口升级重规划) */
	public static final int PYTHON_RETRY_MAX = 3;

	private PythonConstants() {
	}

}
