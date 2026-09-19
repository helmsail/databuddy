package com.helmsail.databuddy.bizdatabase;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 业务库配置 Mapper(系统库 biz_database_config 表):存储读写的唯一落点,业务语义在 BizDatabaseService
 */
@Mapper
public interface BizDatabaseConfigMapper {

	/** 全部配置(按 id 倒序,新加的在前) */
	@Select("SELECT id, name, db_type, username, password, connection_url, description, create_time, update_time FROM biz_database_config ORDER BY id DESC")
	List<BizDatabaseConfig> selectAll();

	/** 按 id 查;不存在返回 null */
	@Select("SELECT id, name, db_type, username, password, connection_url, description, create_time, update_time FROM biz_database_config WHERE id = #{id}")
	BizDatabaseConfig selectById(@Param("id") Long id);

	/** 新增;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO biz_database_config (name, db_type, username, password, connection_url, description) VALUES (#{name}, #{dbType}, #{username}, #{password}, #{connectionUrl}, #{description})")
	void insert(BizDatabaseConfig config);

	/** 按 id 全量更新可变字段(传入的 password 为已加密值) */
	@Update("UPDATE biz_database_config SET name = #{name}, db_type = #{dbType}, username = #{username}, password = #{password}, connection_url = #{connectionUrl}, description = #{description} WHERE id = #{id}")
	void update(BizDatabaseConfig config);

	/** 按 id 删除(物理删) */
	@Delete("DELETE FROM biz_database_config WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

}
