package com.helmsail.databuddy.bizdatabase;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.bizdatabase.jdbc.config.DbConfig;
import com.helmsail.databuddy.bizdatabase.jdbc.model.ColumnInfo;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableData;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableInfo;
import com.helmsail.databuddy.bizdatabase.jdbc.operations.DatabaseOperations;
import com.helmsail.databuddy.bizdatabase.jdbc.pool.JdbcConnectionPoolFactory;
import com.helmsail.databuddy.crypto.AesUtil;
import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 业务库领域服务:配置(biz_database_config)与表关系(biz_table_relation)的统一编排——
 * 写路径不只落表:保存/更新前先直连探测真实库(先验证、再落地),密码出入库在此加解密,
 * 连接信息变更/删除后清掉旧连接池;删除级联清掉该库全部表关系;
 * 读真实库的表清单/表结构(bizdatabase 的表关系编辑器与 agent 选表共用此口)
 */
@Slf4j
@Service
public class BizDatabaseService {

	private final BizDatabaseConfigMapper configMapper;

	private final BizTableRelationMapper relationMapper;

	private final JdbcConnectionPoolFactory poolFactory;

	private final DatabaseOperations databaseOperations;

	/** 落库加密密钥(AES-256-GCM,Base64 32 字节;配置项 databuddy.crypto.aes-key) */
	private final String aesKey;

	public BizDatabaseService(BizDatabaseConfigMapper configMapper, BizTableRelationMapper relationMapper,
			JdbcConnectionPoolFactory poolFactory, DatabaseOperations databaseOperations,
			@Value("${databuddy.crypto.aes-key}") String aesKey) {
		this.configMapper = configMapper;
		this.relationMapper = relationMapper;
		this.poolFactory = poolFactory;
		this.databaseOperations = databaseOperations;
		this.aesKey = aesKey;
	}

	/** 配置列表 */
	public List<BizDatabaseConfig> listConfigs() {
		return configMapper.selectAll();
	}

	/** 新增配置:先直连探测真实库(连通才落库),密码加密落库;返回带 id 的配置 */
	public BizDatabaseConfig saveConfig(BizDatabaseConfig config) {
		validateConfig(config);
		poolFactory.ping(toDbConfig(config, config.getPassword())); // 先验证:真实库连通,才允许落库
		config.setPassword(StringUtils.hasText(config.getPassword()) ? AesUtil.encrypt(config.getPassword(), aesKey) : null);
		configMapper.insert(config);
		log.info("业务库配置新增: id={}, name={}", config.getId(), config.getName());
		return config;
	}

	/** 更新配置:先按新参数直连探测(连通才改),密码留空 = 保持不变;改后清掉旧连接池(按旧连接缓存,下次访问按新配置重建) */
	public BizDatabaseConfig updateConfig(Long id, BizDatabaseConfig config) {
		BizDatabaseConfig old = requireConfig(id);
		validateConfig(config);
		String plainPassword = StringUtils.hasText(config.getPassword()) ? config.getPassword() : toDbConfig(old).getPassword();
		poolFactory.ping(toDbConfig(config, plainPassword)); // 先验证:新参数能连通,才允许改
		config.setId(id);
		config.setPassword(StringUtils.hasText(config.getPassword()) ? AesUtil.encrypt(config.getPassword(), aesKey) : old.getPassword());
		configMapper.update(config);
		poolFactory.remove(toDbConfig(old));
		log.info("业务库配置已更新: id={}, name={}", id, config.getName());
		return configMapper.selectById(id);
	}

	/** 删除配置:物理删;级联清掉该库全部表关系与连接池 */
	@Transactional
	public void deleteConfig(Long id) {
		BizDatabaseConfig old = requireConfig(id);
		relationMapper.deleteByDatabase(id);
		configMapper.deleteById(id);
		poolFactory.remove(toDbConfig(old));
		log.info("业务库配置已删除: id={}, name={}", id, old.getName());
	}

	/** 某库的表清单(直连实时查询;关系编辑器与 agent 选表共用) */
	public List<TableInfo> listTables(Long configId) {
		BizDatabaseConfig config = requireConfig(configId);
		return databaseOperations.listTables(toDbConfig(config));
	}

	/** 某表的结构(直连实时查询) */
	public List<ColumnInfo> listColumns(Long configId, String table) {
		BizDatabaseConfig config = requireConfig(configId);
		return databaseOperations.listColumns(toDbConfig(config), table);
	}

	/** 取配置行(不存在抛 404;图内节点解析目标库元信息用) */
	public BizDatabaseConfig getConfig(Long id) {
		return requireConfig(id);
	}

	/** 执行只读查询(限行/超时由执行器统一施加);供图内 SQL 执行节点用 */
	public TableData executeQuery(Long configId, String sql) {
		BizDatabaseConfig config = requireConfig(configId);
		return databaseOperations.executeSql(toDbConfig(config), sql);
	}

	/** 配置行 → 运行层 DbConfig(密码解密);内部用于换池与连通探测 */
	private DbConfig toDbConfig(BizDatabaseConfig config) {
		String password = StringUtils.hasText(config.getPassword()) ? AesUtil.decrypt(config.getPassword(), aesKey) : null;
		return toDbConfig(config, password);
	}

	/** 某库的全部关系(数据链构建提示词时用) */
	public List<BizTableRelation> listRelations(Long databaseConfigId) {
		return relationMapper.selectByDatabase(databaseConfigId);
	}

	/** 新增关系:所属库必须存在;表名/列名/数量关系必填 */
	public BizTableRelation saveRelation(BizTableRelation relation) {
		if (relation.getDatabaseConfigId() == null || configMapper.selectById(relation.getDatabaseConfigId()) == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "业务库配置不存在: " + relation.getDatabaseConfigId());
		}
		if (!StringUtils.hasText(relation.getSourceTableName()) || !StringUtils.hasText(relation.getSourceColumnName())
				|| !StringUtils.hasText(relation.getTargetTableName()) || !StringUtils.hasText(relation.getTargetColumnName())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "源/目标表名与列名不能为空");
		}
		if (relation.getRelationType() == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "数量关系不能为空");
		}
		relationMapper.insert(relation);
		log.info("表关系新增: id={}, db={}, {}.{} → {}.{}", relation.getId(), relation.getDatabaseConfigId(),
				relation.getSourceTableName(), relation.getSourceColumnName(), relation.getTargetTableName(),
				relation.getTargetColumnName());
		return relation;
	}

	/** 删除关系(物理删) */
	public void deleteRelation(Long id) {
		if (relationMapper.selectById(id) == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "表关系不存在: " + id);
		}
		relationMapper.deleteById(id);
	}

	/** 组装运行层参数(密码已明文);schema 留空:方言层以当前连接库(DATABASE())解析 */
	private DbConfig toDbConfig(BizDatabaseConfig config, String plainPassword) {
		return new DbConfig(config.getDbType(), config.getConnectionUrl(), config.getUsername(), plainPassword, null);
	}

	/** 取配置行;不存在抛 404 */
	private BizDatabaseConfig requireConfig(Long id) {
		BizDatabaseConfig config = configMapper.selectById(id);
		if (config == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "业务库配置不存在: " + id);
		}
		return config;
	}

	/** 配置必填校验:名称 + 连接四要素(dbType / 连接串 / 用户名) */
	private void validateConfig(BizDatabaseConfig config) {
		if (!StringUtils.hasText(config.getName())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "名称不能为空");
		}
		if (config.getDbType() == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "数据库类型不能为空");
		}
		if (!StringUtils.hasText(config.getConnectionUrl())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "连接串不能为空");
		}
		if (!StringUtils.hasText(config.getUsername())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "用户名不能为空");
		}
	}

}

