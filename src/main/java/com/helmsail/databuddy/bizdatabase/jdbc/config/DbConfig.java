package com.helmsail.databuddy.bizdatabase.jdbc.config;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 业务数据库连接配置
 */
@Data
@AllArgsConstructor
public class DbConfig {

	/** 数据库类型 */
	private DbType type;

	/** JDBC 连接串(连接超时等参数建议直接拼在 URL 中) */
	private String url;

	/** 用户名 */
	private String username;

	/** 密码 */
	private String password;

	/** 模式名(MySQL 即库名,可为空) */
	private String schema;

}
