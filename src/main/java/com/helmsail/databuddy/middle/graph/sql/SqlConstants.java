package com.helmsail.databuddy.middle.graph.sql;

/**
 * SQL 域常量(唯一来源):节点 ID 与组重试上限
 */
public final class SqlConstants {

	/** SQL 生成节点(SQL 组头) */
	public static final String SQL_GENERATE = "sql-generate";

	/** SQL 校验节点(SQL 组质检,执行前审文本) */
	public static final String SQL_VALIDATE = "sql-validate";

	/** SQL 执行节点(对业务库运行只读查询) */
	public static final String SQL_EXECUTE = "sql-execute";

	/** SQL 组重试上限(生成即计数;超限升级重规划) */
	public static final int SQL_RETRY_MAX = 3;

	private SqlConstants() {
	}

}
