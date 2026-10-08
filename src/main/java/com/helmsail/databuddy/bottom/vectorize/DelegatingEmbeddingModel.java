package com.helmsail.databuddy.bottom.vectorize;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.helmsail.databuddy.bottom.aimodel.AiModelServiceFactory;
import com.helmsail.databuddy.exception.BusinessException;

/**
 * 委托型嵌入模型:向量侧与工厂之间的唯一门面——嵌入计算转发到"当前激活"实例,
 * 当前模型名与可用性也从此出口,工厂抽象不向业务侧泄露;热切换后立即生效,无需重建 VectorStore bean。
 * 由 VectorStoreConfig 创建并注册为 bean(容器内唯一的 EmbeddingModel 类型 bean)
 */
public class DelegatingEmbeddingModel extends AbstractEmbeddingModel {

	private final AiModelServiceFactory aiModelServiceFactory;

	public DelegatingEmbeddingModel(AiModelServiceFactory aiModelServiceFactory) {
		this.aiModelServiceFactory = aiModelServiceFactory;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		return aiModelServiceFactory.getEmbeddingModel().call(request);
	}

	@Override
	public float[] embed(Document document) {
		return aiModelServiceFactory.getEmbeddingModel().embed(document);
	}

	@Override
	public int dimensions() {
		return aiModelServiceFactory.getEmbeddingModel().dimensions();
	}

	/** 当前激活模型名(向量分区账本:写入打标与过滤表达式构造用;未配置时为 null) */
	public String modelName() {
		return aiModelServiceFactory.getEmbeddingModelName();
	}

	/** 是否已配置嵌入模型(供"未配置则跳过"的路径用):未配置时工厂取值抛业务异常,此处窄捕获转布尔,勿扩为 Exception */
	public boolean available() {
		try {
			aiModelServiceFactory.getEmbeddingModel();
			return true;
		}
		catch (BusinessException e) {
			return false;
		}
	}

}
