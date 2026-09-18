package com.helmsail.databuddy.graph;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 图执行请求
 */
@Data
@AllArgsConstructor
public class GraphRequest {

	/** 用户原始输入 */
	private String query;

	/** 会话标识:带 = 续聊同一会话;不带 = 新会话(服务生成并写回) */
	private String threadId;

}
