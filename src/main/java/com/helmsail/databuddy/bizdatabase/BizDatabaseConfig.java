package com.helmsail.databuddy.bizdatabase;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbType;

import lombok.Data;

/**
 * 业务库配置(biz_database_config):一行 = 一个可分析的库(粒度直接到库,connectionUrl 自带库名)。
 * password 落库为密文(AesUtil 加密);API 只写不回(WRITE_ONLY),更新时留空 = 保持不变
 */
@Data
public class BizDatabaseConfig {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 展示名 */
	private String name;

	/** 数据库类型(复用 jdbc 运行层枚举) */
	private DbType dbType;

	/** 用户名 */
	private String username;

	/** 密码(API 传明文,落库前加密;响应不返回) */
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private String password;

	/** JDBC 连接串(直接指向库;超时等参数拼在 URL 中) */
	private String connectionUrl;

	/** 备注(可空) */
	private String description;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
