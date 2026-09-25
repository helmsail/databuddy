package com.helmsail.databuddy.memory;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会话记忆 Mapper(session_memory 表,系统库):一行 = 一条消息,由全自研实现
 * SummarizingChatMemory 读写;SUMMARY 行为窗口外压缩(每会话至多一行,原地滚动覆盖)
 */
@Mapper
public interface SessionMemoryMapper {

	/** 窗口消息(不含摘要,按写入顺序) */
	@Select("SELECT id, message_type, content FROM session_memory "
			+ "WHERE conversation_id = #{conversationId} AND message_type != 'SUMMARY' ORDER BY id ASC")
	List<SessionMemory> selectMessages(@Param("conversationId") String conversationId);

	/** 最新摘要;无则 null */
	@Select("SELECT content FROM session_memory "
			+ "WHERE conversation_id = #{conversationId} AND message_type = 'SUMMARY' ORDER BY id DESC LIMIT 1")
	String selectSummary(@Param("conversationId") String conversationId);

	/** 摘要原地更新;返回影响行数(0 = 尚无摘要,由调用方首插) */
	@Update("UPDATE session_memory SET content = #{content}, create_time = CURRENT_TIMESTAMP "
			+ "WHERE conversation_id = #{conversationId} AND message_type = 'SUMMARY'")
	int updateSummary(@Param("conversationId") String conversationId, @Param("content") String content);

	/** 追加一条消息 */
	@Insert("INSERT INTO session_memory (conversation_id, message_type, content) "
			+ "VALUES (#{conversationId}, #{messageType}, #{content})")
	void insert(SessionMemory message);

	/** 删除若干条(窗口挤出且已并入摘要的消息) */
	@Delete("<script>DELETE FROM session_memory WHERE conversation_id = #{conversationId} AND id IN "
			+ "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
	void deleteByIds(@Param("conversationId") String conversationId, @Param("ids") List<Long> ids);

	/** 清某会话的全部记忆(含摘要) */
	@Delete("DELETE FROM session_memory WHERE conversation_id = #{conversationId}")
	void deleteByConversation(@Param("conversationId") String conversationId);

}
