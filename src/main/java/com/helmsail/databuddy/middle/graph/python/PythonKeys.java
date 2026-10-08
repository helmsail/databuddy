package com.helmsail.databuddy.middle.graph.python;

/**
 * Python 域状态键(域内自洽:本包节点写、本包分流器读;跨域共用的键在 GraphKeys)
 */
public final class PythonKeys {

	/** 当前 Python 代码(生成节点写;执行节点读) */
	public static final String PYTHON_CODE = "python_code";

	/** Python 组尝试计数(生成即 +1;分析完成清零;超限触发升级) */
	public static final String PYTHON_ATTEMPT = "python_attempt";

	/** Python 组去向标记(组内节点写,分流器读):analyze / regenerate / replan / end */
	public static final String PYTHON_NEXT = "python_next";

	/** Python 失败原因(执行失败/超时/无产出;注入重写提示词) */
	public static final String PYTHON_FAIL_REASON = "python_fail_reason";

	/** Python 标准输出(stdout,约定的 JSON 结果) */
	public static final String PYTHON_RESULT = "python_result";

	private PythonKeys() {
	}

}
