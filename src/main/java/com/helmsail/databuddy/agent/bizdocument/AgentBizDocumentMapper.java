package com.helmsail.databuddy.agent.bizdocument;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.helmsail.databuddy.agent.EmbeddingStatus;
import com.helmsail.databuddy.vectorize.splitter.SplitterType;

/**
 * 文档 Mapper(系统库 agent_biz_document 表):单表 SQL,存储读写的唯一落点;向量同步编排在 AgentBizDocumentService
 */
@Mapper
public interface AgentBizDocumentMapper {

	/** 某 agent 的文档清单(按 id 升序) */
	@Select("SELECT id, agent_id, name, storage_type, storage_path, splitter_type, embedding_status, error_msg, create_time, update_time "
			+ "FROM agent_biz_document WHERE agent_id = #{agentId} ORDER BY id")
	List<AgentBizDocument> selectByAgent(@Param("agentId") Long agentId);

	/** 按 id 查;不存在返回 null */
	@Select("SELECT id, agent_id, name, storage_type, storage_path, splitter_type, embedding_status, error_msg, create_time, update_time "
			+ "FROM agent_biz_document WHERE id = #{id}")
	AgentBizDocument selectById(@Param("id") Long id);

	/** 按 agent + 文档名查(上传前重名预检) */
	@Select("SELECT id, agent_id, name, storage_type, storage_path, splitter_type, embedding_status, error_msg, create_time, update_time "
			+ "FROM agent_biz_document WHERE agent_id = #{agentId} AND name = #{name}")
	AgentBizDocument selectByAgentAndName(@Param("agentId") Long agentId, @Param("name") String name);

	/** 存在未同步行(PENDING / FAILED)的 agent 清单(定时兜底扫描用) */
	@Select("SELECT DISTINCT agent_id FROM agent_biz_document WHERE embedding_status <> 'SYNCED'")
	List<Long> selectAgentIdsUnsynced();

	/** 新增;回填自增 id(create_time/update_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO agent_biz_document (agent_id, name, storage_type, storage_path, splitter_type, embedding_status) "
			+ "VALUES (#{agentId}, #{name}, #{storageType}, #{storagePath}, #{splitterType}, #{embeddingStatus})")
	void insert(AgentBizDocument document);

	/** 按 id 更新可变字段(文档名 / 切分策略) */
	@Update("UPDATE agent_biz_document SET name = #{name}, splitter_type = #{splitterType} WHERE id = #{id}")
	void update(@Param("id") Long id, @Param("name") String name, @Param("splitterType") SplitterType splitterType);

	/** 更新同步结果:成功置 SYNCED 时 errorMsg 传 null 清空原因 */
	@Update("UPDATE agent_biz_document SET embedding_status = #{status}, error_msg = #{errorMsg} WHERE id = #{id}")
	void updateSyncStatus(@Param("id") Long id, @Param("status") EmbeddingStatus status,
			@Param("errorMsg") String errorMsg);

	/** 按 id 删除(物理删;文件本体由 Service 走 storage 包删除) */
	@Delete("DELETE FROM agent_biz_document WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

}
