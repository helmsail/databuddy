package com.helmsail.databuddy.graph.sql;

/**
 * SQL 域状态键(域内自洽:本包节点写、本包分流器读;跨域共用的键在 GraphKeys)
 */
public final class SqlKeys {

	/** SQL 组尝试计数(生成即 +1;执行成功清零;超限触发升级) */
	public static final String SQL_ATTEMPT = "sql_attempt";

	/** SQL 组去向标记(组内节点写,分流器读):semantic / regenerate / replan / end / hub */
	public static final String SQL_NEXT = "sql_next";

	/** SQL 打回原因(语义不过 / 执行失败;生成成功时清空) */
	public static final String SQL_REPAIR_REASON = "sql_repair_reason";

	/** 语义一致性结果(校验节点写,分流器读;未通过原因写 SQL_REPAIR_REASON 打回生成) */
	public static final String SEMANTIC_PASSED = "semantic_passed";

	private SqlKeys() {
	}

}
