package com.helmsail.databuddy.memory;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 会话记忆 Mapper(系统库 session_memory 表):存储读写的唯一落点,业务语义在 SessionMemoryService
 */
@Mapper
public interface SessionMemoryMapper {

	/** 插入一条记忆条目(原文轮 / 压缩条目通用);回填自增 id */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO session_memory (session_id, kind, question, answer) VALUES (#{sessionId}, #{kind}, #{question}, #{answer})")
	void insert(SessionMemory entry);

	/** 最近 limit 条原文轮(id 倒序) */
	@Select("SELECT id, session_id, kind, question, answer, create_time FROM session_memory WHERE session_id = #{sessionId} AND kind = 'turn' ORDER BY id DESC LIMIT #{limit}")
	List<SessionMemory> selectRecentTurns(@Param("sessionId") String sessionId, @Param("limit") int limit);

	/** 最新压缩条目;不存在返回 null */
	@Select("SELECT id, session_id, kind, question, answer, create_time FROM session_memory WHERE session_id = #{sessionId} AND kind = 'summary' ORDER BY id DESC LIMIT 1")
	SessionMemory selectLatestSummary(@Param("sessionId") String sessionId);

	/** 删除最后一条原文轮(被拒回退);无行时空操作 */
	@Delete("DELETE FROM session_memory WHERE session_id = #{sessionId} AND kind = 'turn' ORDER BY id DESC LIMIT 1")
	void deleteLatestTurn(@Param("sessionId") String sessionId);

	/** 删除 id 不超过 maxId 的原文轮(压缩完成后的溢出清理) */
	@Delete("DELETE FROM session_memory WHERE session_id = #{sessionId} AND kind = 'turn' AND id <= #{maxId}")
	void deleteOverflowTurns(@Param("sessionId") String sessionId, @Param("maxId") Long maxId);

	/** 删除除 keepId 外的全部压缩条目(压缩写序最后一步:清旧摘要) */
	@Delete("DELETE FROM session_memory WHERE session_id = #{sessionId} AND kind = 'summary' AND id <> #{keepId}")
	void deleteOldSummaries(@Param("sessionId") String sessionId, @Param("keepId") Long keepId);

	/** 删除某线程键的全部记忆条目(图侧清记忆接口用;客户端删会话编排时调用) */
	@Delete("DELETE FROM session_memory WHERE session_id = #{sessionId}")
	void deleteBySession(@Param("sessionId") String sessionId);

}
