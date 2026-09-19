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

	/** 某 agent 的全部绑定行(按 id 升序 = 绑定先后) */
	@Select("SELECT id, agent_id, database_config_id, table_name, embedding_status, error_msg, create_time, update_time "
			+ "FROM agent_biz_table WHERE agent_id = #{agentId} ORDER BY id")
	List<AgentBizTable> selectByAgent(@Param("agentId") Long agentId);

	/** 存在未同步行(PENDING / FAILED)的 agent 清单(定时兜底扫描用) */
	@Select("SELECT DISTINCT agent_id FROM agent_biz_table WHERE embedding_status <> 'SYNCED'")
	List<Long> selectAgentIdsUnsynced();

	/** 新增绑定行;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO agent_biz_table (agent_id, database_config_id, table_name, embedding_status) "
			+ "VALUES (#{agentId}, #{databaseConfigId}, #{tableName}, #{embeddingStatus})")
	void insert(AgentBizTable row);

	/** 更新同步结果:成功置 SYNCED 时 errorMsg 传 null 清空原因 */
	@Update("UPDATE agent_biz_table SET embedding_status = #{status}, error_msg = #{errorMsg} WHERE id = #{id}")
	void updateSyncStatus(@Param("id") Long id, @Param("status") EmbeddingStatus status,
			@Param("errorMsg") String errorMsg);

	/** 按 id 删除(物理删) */
	@Delete("DELETE FROM agent_biz_table WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

}
