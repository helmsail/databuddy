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

	/** 某会话的消息(时间正序;id 单调,同秒并发也稳定) */
	@Select("SELECT id, session_id, role, content, message_type, create_time FROM session_message "
			+ "WHERE session_id = #{sessionId} ORDER BY id")
	List<SessionMessage> selectBySession(@Param("sessionId") String sessionId);

	/** 按 id 查(保存后回读时间戳用) */
	@Select("SELECT id, session_id, role, content, message_type, create_time FROM session_message WHERE id = #{id}")
	SessionMessage selectById(@Param("id") Long id);

	/** 新增;回填自增 id(create_time 由数据库默认值维护) */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO session_message (session_id, role, content, message_type) VALUES (#{sessionId}, #{role}, #{content}, #{messageType})")
	void insert(SessionMessage message);

	/** 按会话删除(删会话级联) */
	@Delete("DELETE FROM session_message WHERE session_id = #{sessionId}")
	void deleteBySession(@Param("sessionId") String sessionId);

}
