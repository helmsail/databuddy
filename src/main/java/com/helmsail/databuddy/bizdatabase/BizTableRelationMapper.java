package com.helmsail.databuddy.bizdatabase;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 表级关联 Mapper(系统库 biz_table_relation 表):存储读写的唯一落点,业务语义在 BizDatabaseService
 */
@Mapper
public interface BizTableRelationMapper {

	/** 按 id 查;不存在返回 null */
	@Select("SELECT id, database_config_id, source_table_name, source_column_name, target_table_name, target_column_name, relation_type, description, create_time, update_time FROM biz_table_relation WHERE id = #{id}")
	BizTableRelation selectById(@Param("id") Long id);

	/** 某库的全部关系(按 id 升序) */
	@Select("SELECT id, database_config_id, source_table_name, source_column_name, target_table_name, target_column_name, relation_type, description, create_time, update_time FROM biz_table_relation WHERE database_config_id = #{databaseConfigId} ORDER BY id")
	List<BizTableRelation> selectByDatabase(@Param("databaseConfigId") Long databaseConfigId);

	/** 新增;回填自增 id */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO biz_table_relation (database_config_id, source_table_name, source_column_name, target_table_name, target_column_name, relation_type, description) VALUES (#{databaseConfigId}, #{sourceTableName}, #{sourceColumnName}, #{targetTableName}, #{targetColumnName}, #{relationType}, #{description})")
	void insert(BizTableRelation relation);

	/** 按 id 删除(物理删) */
	@Delete("DELETE FROM biz_table_relation WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

	/** 删除某库的全部关系(删库配置时级联清理) */
	@Delete("DELETE FROM biz_table_relation WHERE database_config_id = #{databaseConfigId}")
	void deleteByDatabase(@Param("databaseConfigId") Long databaseConfigId);

}
