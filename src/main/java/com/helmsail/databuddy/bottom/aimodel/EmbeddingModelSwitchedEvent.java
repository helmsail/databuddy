package com.helmsail.databuddy.bottom.aimodel;

/**
 * EMBEDDING 模型切换事件:切换成功后发布,监听侧把各 agent 四类知识的已同步行标记失效(原因=本事件),
 * 由重试管线(手动 / 定时)在新模型分区重建;发布方与监听方以事件解耦——aimodel 反向直调业务域会成包环
 */
public record EmbeddingModelSwitchedEvent(String previousModel, String currentModel) {

	/** 切换原因文本(落 error_msg;此前未配置嵌入模型时只写新模型名) */
	public String reason() {
		return previousModel == null ? "模型切换: " + currentModel
				: "模型切换: " + previousModel + " -> " + currentModel;
	}

}
