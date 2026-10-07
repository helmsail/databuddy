package com.helmsail.databuddy.memory;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * agent 记忆 Mapper(系统库 agent_memory 表):单表 SQL,存储读写的唯一落点;沉淀语义编排在 MemoryService
 */
@Mapper
public interface AgentMemoryMapper {

	/** 全列清单(加列只改这一处) */
	String ALL_COLUMNS = "id, agent_id, content, create_time, update_time";

	/** 新增记忆行;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO agent_memory (agent_id, content) VALUES (#{agentId}, #{content})")
	void insert(AgentMemory memory);

	/** 重写一条记忆内容 */
	@Update("UPDATE agent_memory SET content = #{content} WHERE agent_id = #{agentId} AND id = #{id}")
	void update(@Param("agentId") Long agentId, @Param("id") Long id, @Param("content") String content);

	/** 按 agent + id 删除(物理删) */
	@Delete("DELETE FROM agent_memory WHERE agent_id = #{agentId} AND id = #{id}")
	void deleteById(@Param("agentId") Long agentId, @Param("id") Long id);

	/** 清某 agent 的全部记忆(agent 级联删除编排调用) */
	@Delete("DELETE FROM agent_memory WHERE agent_id = #{agentId}")
	void deleteByAgent(@Param("agentId") Long agentId);

	/** 某 agent 的全部记忆(按 id 升序 = 沉淀先后) */
	@Select("SELECT " + ALL_COLUMNS + " FROM agent_memory WHERE agent_id = #{agentId} ORDER BY id")
	List<AgentMemory> selectByAgent(@Param("agentId") Long agentId);

}
