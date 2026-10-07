package com.helmsail.databuddy.agent.biztable;

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
 * 表绑定 Mapper(系统库 agent_biz_table 表):单表 SQL,存储读写的唯一落点;向量同步编排在 AgentBizTableService
 */
@Mapper
public interface AgentBizTableMapper {

	/** 全列清单:两处查询共用,加列只改这一处 */
	String ALL_COLUMNS = "id, agent_id, database_config_id, table_name, embedding_status, error_msg, create_time, update_time";

	/** 新增绑定行;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO agent_biz_table (agent_id, database_config_id, table_name, embedding_status) "
			+ "VALUES (#{agentId}, #{databaseConfigId}, #{tableName}, #{embeddingStatus})")
	void insert(AgentBizTable row);

	/** 按 agent + id 删除(物理删) */
	@Delete("DELETE FROM agent_biz_table WHERE agent_id = #{agentId} AND id = #{id}")
	void deleteById(@Param("agentId") Long agentId, @Param("id") Long id);

	/** 某 agent 的全部绑定行(按 id 升序 = 绑定先后) */
	@Select("SELECT " + ALL_COLUMNS + " FROM agent_biz_table WHERE agent_id = #{agentId} ORDER BY id")
	List<AgentBizTable> selectByAgent(@Param("agentId") Long agentId);

	/** 状态回执(CAS):仅当行仍为 from 态才落入 to 态,返回影响行数(0 = 状态已变或行已删);成功传 null 清原因 */
	@Update("UPDATE agent_biz_table SET embedding_status = #{to}, error_msg = #{errorMsg} "
			+ "WHERE agent_id = #{agentId} AND id = #{id} AND embedding_status = #{from}")
	int updateSyncStatus(@Param("agentId") Long agentId, @Param("id") Long id, @Param("from") EmbeddingStatus from,
			@Param("to") EmbeddingStatus to, @Param("errorMsg") String errorMsg);

	/** 某 agent 指定状态的绑定行(重试 / 定时兜底 / 状态分类共用:要哪个状态传哪个) */
	@Select("SELECT " + ALL_COLUMNS + " FROM agent_biz_table "
			+ "WHERE agent_id = #{agentId} AND embedding_status = #{status} ORDER BY id")
	List<AgentBizTable> selectByAgentAndStatus(@Param("agentId") Long agentId,
			@Param("status") EmbeddingStatus status);

}
