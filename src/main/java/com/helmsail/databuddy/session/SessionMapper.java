package com.helmsail.databuddy.session;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会话 Mapper(系统库 session 表):单表 SQL,存储读写的唯一落点;业务语义在 SessionService
 */
@Mapper
public interface SessionMapper {

	/** 某 agent 的会话列表(最近活跃在前) */
	@Select("SELECT id, agent_id, title, create_time, update_time FROM session "
			+ "WHERE agent_id = #{agentId} ORDER BY update_time DESC, create_time DESC")
	List<Session> selectByAgent(@Param("agentId") Long agentId);

	/** 按会话键查;不存在返回 null */
	@Select("SELECT id, agent_id, title, create_time, update_time FROM session WHERE id = #{id}")
	Session selectById(@Param("id") String id);

	/** 新建(UUID 主键由服务层生成;时间列由数据库默认值维护) */
	@Insert("INSERT INTO session (id, agent_id, title) VALUES (#{id}, #{agentId}, #{title})")
	void insert(Session session);

	/** 活跃时间 touch(存消息时调用,列表按它排序;UPDATE 触发 update_time 自动刷新) */
	@Update("UPDATE session SET update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
	void touch(@Param("id") String id);

	/** 填标题(仅首条消息且 title 为空时调用;顺带刷新 update_time) */
	@Update("UPDATE session SET title = #{title} WHERE id = #{id}")
	void updateTitle(@Param("id") String id, @Param("title") String title);

	/** 按会话键删除(消息由 SessionService 级联先清) */
	@Delete("DELETE FROM session WHERE id = #{id}")
	void deleteById(@Param("id") String id);

}
