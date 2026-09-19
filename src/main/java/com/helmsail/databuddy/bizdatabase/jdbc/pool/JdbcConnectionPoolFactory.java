package com.helmsail.databuddy.bizdatabase.jdbc.pool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbConfig;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import jakarta.annotation.PreDestroy;

/**
 * 连接池工厂:按数据源缓存连接池(创建开销大,必须复用;配置变更时 remove 后重建)
 */
@Component
public class JdbcConnectionPoolFactory {

	private final ConcurrentHashMap<String, JdbcConnectionPool> pools = new ConcurrentHashMap<>();

	/** 获取(或懒创建)连接池,key 为 url + 用户名 */
	public JdbcConnectionPool get(DbConfig config) {
		return pools.computeIfAbsent(key(config), k -> new JdbcConnectionPool(config));
	}

	/** 移除并关闭连接池,用于数据源配置变更或删除 */
	public void remove(DbConfig config) {
		JdbcConnectionPool pool = pools.remove(key(config));
		if (pool != null) {
			pool.close();
		}
	}

	/**
	 * 连通性测试:绕过连接池直连(不产生池缓存,适合保存数据源前的"测试连接");失败抛业务异常
	 */
	public void ping(DbConfig config) {
		try (Connection connection = DriverManager.getConnection(config.getUrl(), config.getUsername(),
				config.getPassword())) {
			// 能建立连接即视为连通
		}
		catch (SQLException e) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "数据库连接失败: " + e.getMessage(), e);
		}
	}

	@PreDestroy
	public void close() {
		pools.values().forEach(JdbcConnectionPool::close);
		pools.clear();
	}

	private String key(DbConfig config) {
		return config.getUrl() + "|" + config.getUsername();
	}

}
