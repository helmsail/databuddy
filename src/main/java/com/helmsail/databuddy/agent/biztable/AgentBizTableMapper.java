package com.helmsail.databuddy.agent.biztable;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 表绑定 Mapper(系统库 agent_biz_table 表):单表 SQL,存储读写的唯一落点;向量刷新编排在 AgentBizTableService。
 * 无向量状态字段:业务库结构会漂移,SYNCED 不代表新鲜,重刷由人工入口实时查库完成
 */
@Mapper
public interface AgentBizTableMapper {

	/** 全列清单(加列只改这一处) */
	String ALL_COLUMNS = "id, agent_id, database_config_id, table_name, create_time, update_time";

	/** 新增绑定行;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO agent_biz_table (agent_id, database_config_id, table_name) "
			+ "VALUES (#{agentId}, #{databaseConfigId}, #{tableName})")
	void insert(AgentBizTable row);

	/** 按 agent + id 删除(物理删) */
	@Delete("DELETE FROM agent_biz_table WHERE agent_id = #{agentId} AND id = #{id}")
	void deleteById(@Param("agentId") Long agentId, @Param("id") Long id);

	/** 某 agent 的全部绑定行(按 id 升序 = 绑定先后) */
	@Select("SELECT " + ALL_COLUMNS + " FROM agent_biz_table WHERE agent_id = #{agentId} ORDER BY id")
	List<AgentBizTable> selectByAgent(@Param("agentId") Long agentId);

}
