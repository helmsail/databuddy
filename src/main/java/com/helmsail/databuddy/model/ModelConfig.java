package com.helmsail.databuddy.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * AI 模型配置(OpenAI 兼容协议)
 */
@Data
@AllArgsConstructor
public class ModelConfig {

	/** 模型类型 */
	private ModelType type;

	/** 服务地址(如 https://api.deepseek.com) */
	private String baseUrl;

	/** API Key(可空,兼容本地无鉴权部署) */
	private String apiKey;

	/** 模型名称(如 deepseek-chat) */
	private String modelName;

	/** 采样温度(可空,仅对话模型使用,作为实例默认值) */
	private Double temperature;

	/** 最大生成 token 数(可空,仅对话模型使用,作为实例默认值) */
	private Integer maxTokens;

}
