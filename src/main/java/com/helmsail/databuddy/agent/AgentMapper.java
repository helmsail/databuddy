package com.helmsail.databuddy.agent;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 智能体 Mapper(系统库 agent 表):单表 SQL,存储读写的唯一落点;业务语义(删除级联、绑定关系)在 AgentService
 */
@Mapper
public interface AgentMapper {

	/** 全部智能体(按 id 倒序,新加的在前) */
	@Select("SELECT id, name, description, create_time, update_time FROM agent ORDER BY id DESC")
	List<Agent> selectAll();

	/** 按 id 查;不存在返回 null */
	@Select("SELECT id, name, description, create_time, update_time FROM agent WHERE id = #{id}")
	Agent selectById(@Param("id") Long id);

	/** 新增;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO agent (name, description) VALUES (#{name}, #{description})")
	void insert(Agent agent);

	/** 按 id 更新可变字段 */
	@Update("UPDATE agent SET name = #{name}, description = #{description} WHERE id = #{id}")
	void update(Agent agent);

	/** 按 id 删除(物理删) */
	@Delete("DELETE FROM agent WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

}
