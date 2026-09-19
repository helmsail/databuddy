package com.helmsail.databuddy.agent.bizterm;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.helmsail.databuddy.agent.EmbeddingStatus;

/**
 * 术语 Mapper(系统库 agent_biz_term 表):单表 SQL,存储读写的唯一落点;向量同步编排在 AgentBizTermService
 */
@Mapper
public interface AgentBizTermMapper {

	/** 某 agent 的术语清单(按 id 升序) */
	@Select("SELECT id, agent_id, business_term, synonyms, description, embedding_status, error_msg, create_time, update_time "
			+ "FROM agent_biz_term WHERE agent_id = #{agentId} ORDER BY id")
	List<AgentBizTerm> selectByAgent(@Param("agentId") Long agentId);

	/** 按 id 查;不存在返回 null */
	@Select("SELECT id, agent_id, business_term, synonyms, description, embedding_status, error_msg, create_time, update_time "
			+ "FROM agent_biz_term WHERE id = #{id}")
	AgentBizTerm selectById(@Param("id") Long id);

	/** 存在未同步行(PENDING / FAILED)的 agent 清单(定时兜底扫描用) */
	@Select("SELECT DISTINCT agent_id FROM agent_biz_term WHERE embedding_status <> 'SYNCED'")
	List<Long> selectAgentIdsUnsynced();

	/** 新增;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO agent_biz_term (agent_id, business_term, synonyms, description, embedding_status) "
			+ "VALUES (#{agentId}, #{businessTerm}, #{synonyms}, #{description}, #{embeddingStatus})")
	void insert(AgentBizTerm term);

	/** 按 id 更新可变字段(术语 / 同义词 / 释义) */
	@Update("UPDATE agent_biz_term SET business_term = #{businessTerm}, synonyms = #{synonyms}, description = #{description} WHERE id = #{id}")
	void update(AgentBizTerm term);

	/** 更新同步结果:成功置 SYNCED 时 errorMsg 传 null 清空原因 */
	@Update("UPDATE agent_biz_term SET embedding_status = #{status}, error_msg = #{errorMsg} WHERE id = #{id}")
	void updateSyncStatus(@Param("id") Long id, @Param("status") EmbeddingStatus status,
			@Param("errorMsg") String errorMsg);

	/** 按 id 删除(物理删) */
	@Delete("DELETE FROM agent_biz_term WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

}
