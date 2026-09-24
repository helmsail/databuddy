package com.helmsail.databuddy.aimodel;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * AI 模型配置(OpenAI 兼容协议):ai_model_config 表实体,同时是模型工厂的入参。
 * 调优参数为空 = 用服务商默认值;is_active:1 = 激活,NULL = 未激活(同类型至多一个激活)
 */
@Data
public class AiModelConfig {

	/** 主键(新增时由数据库回填) */
	private Long id;

	/** 模型类型:CHAT / EMBEDDING(创建后不可改) */
	private AiModelType modelType;

	/** 模型名称(如 deepseek-chat) */
	private String modelName;

	/** 服务地址(如 https://api.deepseek.com) */
	private String baseUrl;

	/** API Key(可空,兼容本地无鉴权部署) */
	private String apiKey;

	/** 采样温度(可空,仅对话模型使用,作为实例默认值) */
	private Double temperature;

	/** 最大生成 token 数(可空,同上) */
	private Integer maxTokens;

	/** 核采样阈值(可空,0~1;与温度同类旋钮,一般只调其一) */
	private Double topP;

	/** 激活标记:true = 激活;null = 未激活(不落 0) */
	private Boolean isActive;

	/** 创建时间 */
	private LocalDateTime createTime;

	/** 更新时间 */
	private LocalDateTime updateTime;

}
