package com.helmsail.databuddy.agent.bizqa;

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
 * 问答 Mapper(系统库 agent_biz_qa 表):单表 SQL,存储读写的唯一落点;向量同步编排在 AgentBizQaService
 */
@Mapper
public interface AgentBizQaMapper {

	/** 某 agent 的问答清单(按 id 升序) */
	@Select("SELECT id, agent_id, question, content, embedding_status, error_msg, create_time, update_time "
			+ "FROM agent_biz_qa WHERE agent_id = #{agentId} ORDER BY id")
	List<AgentBizQa> selectByAgent(@Param("agentId") Long agentId);

	/** 按 id 查;不存在返回 null */
	@Select("SELECT id, agent_id, question, content, embedding_status, error_msg, create_time, update_time "
			+ "FROM agent_biz_qa WHERE id = #{id}")
	AgentBizQa selectById(@Param("id") Long id);

	/** 存在未同步行(PENDING / FAILED)的 agent 清单(定时兜底扫描用) */
	@Select("SELECT DISTINCT agent_id FROM agent_biz_qa WHERE embedding_status <> 'SYNCED'")
	List<Long> selectAgentIdsUnsynced();

	/** 新增;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO agent_biz_qa (agent_id, question, content, embedding_status) "
			+ "VALUES (#{agentId}, #{question}, #{content}, #{embeddingStatus})")
	void insert(AgentBizQa qa);

	/** 按 id 更新可变字段(问题 / 答案) */
	@Update("UPDATE agent_biz_qa SET question = #{question}, content = #{content} WHERE id = #{id}")
	void update(AgentBizQa qa);

	/** 更新同步结果:成功置 SYNCED 时 errorMsg 传 null 清空原因 */
	@Update("UPDATE agent_biz_qa SET embedding_status = #{status}, error_msg = #{errorMsg} WHERE id = #{id}")
	void updateSyncStatus(@Param("id") Long id, @Param("status") EmbeddingStatus status,
			@Param("errorMsg") String errorMsg);

	/** 按 id 删除(物理删) */
	@Delete("DELETE FROM agent_biz_qa WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

}
