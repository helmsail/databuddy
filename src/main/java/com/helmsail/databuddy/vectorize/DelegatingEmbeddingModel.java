package com.helmsail.databuddy.vectorize;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.helmsail.databuddy.aimodel.AiModelServiceFactory;

/**
 * 委托型嵌入模型:VectorStore 持有的稳定引用——每次写入/检索转发到工厂的"当前激活"实例。
 * 嵌入模型热切换后立即生效,无需重建 VectorStore bean;由 VectorStoreConfig 手动创建,刻意不注册为 bean
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

}
