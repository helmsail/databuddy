package com.helmsail.databuddy.jdbc.pool;

import java.sql.Connection;
import java.sql.SQLException;

import com.alibaba.druid.pool.DruidDataSource;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;
import com.helmsail.databuddy.jdbc.config.DbConfig;

/**
 * 业务数据库连接池:基于 Druid 封装,每个数据源一个实例。
 * 池参数保持 Druid 默认,唯一覆盖 maxWait(默认 -1 无限等待,必须封顶)
 */
public class JdbcConnectionPool implements AutoCloseable {

	/** 获取连接的最大尝试次数 */
	private static final int MAX_ATTEMPTS = 2;

	/** 重试退避基数(毫秒),第 n 次重试等待 n * 基数 */
	private static final long RETRY_BACKOFF_MILLIS = 500L;

	/** 获取连接的最长等待时间(毫秒),覆盖 Druid 默认的 -1(无限等待) */
	private static final long MAX_WAIT_MILLIS = 10_000L;

	private final DruidDataSource dataSource;

	public JdbcConnectionPool(DbConfig config) {
		DruidDataSource dataSource = new DruidDataSource();
		dataSource.setUrl(config.getUrl());
		dataSource.setUsername(config.getUsername());
		dataSource.setPassword(config.getPassword());
		dataSource.setMaxWait(MAX_WAIT_MILLIS);
		this.dataSource = dataSource;
	}

	/** 获取连接,失败时退避重试;耗尽次数后抛出异常 */
	public Connection getConnection() {
		for (int attempt = 1;; attempt++) {
			try {
				return dataSource.getConnection();
			}
			catch (SQLException e) {
				if (attempt >= MAX_ATTEMPTS) {
					throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取数据库连接失败: " + e.getMessage(), e);
				}
				try {
					Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
				}
				catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();
					throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取数据库连接被中断", interruptedException);
				}
			}
		}
	}

	@Override
	public void close() {
		dataSource.close();
	}

}
