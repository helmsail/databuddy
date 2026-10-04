package com.helmsail.databuddy.session;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 会话消息 Mapper(系统库 session_message 表):单表 SQL,存储读写的唯一落点
 */
@Mapper
public interface SessionMessageMapper {

	/** 新增;回填自增 id(create_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO session_message (session_id, role, content) VALUES (#{sessionId}, #{role}, #{content})")
	void insert(SessionMessage message);

	/** 按会话删除(删会话级联) */
	@Delete("DELETE FROM session_message WHERE session_id = #{sessionId}")
	void deleteBySession(@Param("sessionId") String sessionId);

	/** 会话消息单页:按会话倒序取最新 limit 条;beforeId 非空 = 只取更早一页(id 单调当游标,调用方反转为时间正序) */
	@Select("<script>SELECT id, session_id, role, content, create_time FROM session_message "
			+ "WHERE session_id = #{sessionId}"
			+ "<if test='beforeId != null'> AND id &lt; #{beforeId}</if>"
			+ " ORDER BY id DESC LIMIT #{limit}</script>")
	List<SessionMessage> selectBySession(@Param("sessionId") String sessionId, @Param("beforeId") Long beforeId,
			@Param("limit") int limit);

}
