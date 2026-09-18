package com.helmsail.databuddy.graph;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * SSE 数据:节点完成时推给前端的一条数据(每条 SSE 消息的 data 部分)
 */
@Data
@AllArgsConstructor
public class GraphNodeResponse {

	/** 运行标识(会话) */
	private String threadId;

	/** 节点名(error 表示执行失败) */
	private String node;

	/** 该节点的展示数据(白名单提取) */
	private Map<String, Object> data;

}
