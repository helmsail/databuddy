package com.helmsail.databuddy.middle.graph;

import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Data;

/**
 * 图的 SSE 数据块(对外契约):一个对象 = 一帧的 data;eventType 即 SSE 的 event 名(取值即本类帧类型常量)
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphSseChunk {

	// —— 帧类型词表(取值即 SSE 的 event 名,不进 OverAllState) ——

	/** 文本帧:流式片段或整段文本 */
	public static final String TEXT = "text";

	/** 完成帧:本次执行正常结束 */
	public static final String DONE = "done";

	/** 错误帧:本次执行失败 */
	public static final String ERROR = "error";

	/** 过程帧:节点完成的轻量播报(结构化事件,与正文分离;对齐 AG-UI STEP / Dify node_finished) */
	public static final String STEP = "step";

	/** 计划帧:待确认的执行计划(text = 计划 JSON;人工确认闸挂起时下发) */
	public static final String PLAN = "plan";

	/** SQL 帧:新生成的 SQL 文本(text = SQL;去重后按需下发) */
	public static final String SQL = "sql";

	/** 结果帧:SQL 执行结果(text = 结果 JSON:{step,sql,columns,rows,row_count,truncated}) */
	public static final String RESULT = "result";

	/** 会话键(= 图线程键;客户端据此停止/续跑) */
	private String sessionId;

	/** 文本来源节点(协议帧/汇总帧为空) */
	private String node;

	private String text;

	private String eventType;

	/** 错误帧(单帧流 / 流内兜底共用):EventSource 读不到 HTTP 信封,流内 error 是唯一可达通道 */
	public static ServerSentEvent<GraphSseChunk> errorFrame(String sessionId, String message) {
		GraphSseChunk chunk = builder().eventType(GraphSseChunk.ERROR).text(message).build();
		chunk.setSessionId(sessionId);
		return ServerSentEvent.builder(chunk).event(GraphSseChunk.ERROR).build();
	}

}
