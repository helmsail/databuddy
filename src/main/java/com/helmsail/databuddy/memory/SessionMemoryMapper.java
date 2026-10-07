package com.helmsail.databuddy.memory;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会话记忆 Mapper(session_memory 单表,系统库):数据侧只有"行"的普通操作,不区分消息行与摘要行;
 * 窗口裁剪与超窗压缩的策略全在 MemoryService
 */
@Mapper
public interface SessionMemoryMapper {

	/** 会话全部行(按写入顺序;内容自带标识,数据侧不解释) */
	@Select("SELECT id, content FROM session_memory WHERE session_id = #{sessionId} ORDER BY id ASC")
	List<SessionMemory> selectBySession(@Param("sessionId") String sessionId);

	/** 追加一行 */
	@Insert("INSERT INTO session_memory (session_id, content) VALUES (#{sessionId}, #{content})")
	void insert(SessionMemory row);

	/** 更新一行内容(压缩后摘要写回最老行) */
	@Update("UPDATE session_memory SET content = #{content} WHERE id = #{id}")
	void updateContent(@Param("id") long id, @Param("content") String content);

	/** 删除若干行(已并入摘要的被压缩行) */
	@Delete("<script>DELETE FROM session_memory WHERE session_id = #{sessionId} AND id IN "
			+ "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
	void deleteByIds(@Param("sessionId") String sessionId, @Param("ids") List<Long> ids);

	/** 清某会话全部行(消息 + 摘要) */
	@Delete("DELETE FROM session_memory WHERE session_id = #{sessionId}")
	void deleteBySession(@Param("sessionId") String sessionId);

}
