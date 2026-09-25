package com.helmsail.databuddy.graph;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Data;

/**
 * 图的 SSE 数据块(对外契约):一个对象 = 一帧的 data;eventType 即 SSE 的 event 名(取值见 GraphKeys)
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphSseChunk {

	/** 会话键(= 图线程键;客户端据此停止/续跑) */
	private String sessionId;

	/** 文本来源节点(协议帧/汇总帧为空) */
	private String node;

	private String text;

	private String eventType;

}
