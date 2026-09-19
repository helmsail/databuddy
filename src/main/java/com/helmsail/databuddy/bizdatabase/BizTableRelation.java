package com.helmsail.databuddy.bizdatabase;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 表级关联(biz_table_relation):一行 = 一条列对关联(sourceTable.sourceColumn → targetTable.targetColumn)。
 * 归属某个业务库配置;表/列存名字(表是"活"的,不建表元数据表)
 */
@Data
public class BizTableRelation {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 所属业务库配置 id */
	private Long databaseConfigId;

	/** 源表名 */
	private String sourceTableName;

	/** 源列名 */
	private String sourceColumnName;

	/** 目标表名 */
	private String targetTableName;

	/** 目标列名 */
	private String targetColumnName;

	/** 数量关系(方向:source → target) */
	private RelationType relationType;

	/** 备注(可空) */
	private String description;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
