package com.helmsail.databuddy.bottom.vectorize;

import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.helmsail.databuddy.bottom.aimodel.AiModelServiceFactory;

/**
 * 向量存储装配:当前用内存实现(SimpleVectorStore,重启丢向量、数据可重建);
 * 后续选定真实向量库 = 换对应 starter + 只改这一个 bean,业务代码零改动
 */
@Configuration
public class VectorStoreConfig {

	/**
	 * 委托实例单独成 bean:VectorStore 与向量业务共用同一门面(工厂抽象不外泄);
	 * 这是容器内唯一的 EmbeddingModel 类型 bean——未来引入外部嵌入 starter 时留意类型歧义
	 */
	@Bean
	public DelegatingEmbeddingModel delegatingEmbeddingModel(AiModelServiceFactory aiModelServiceFactory) {
		return new DelegatingEmbeddingModel(aiModelServiceFactory);
	}

	@Bean
	public VectorStore vectorStore(DelegatingEmbeddingModel embeddingModel) {
		return SimpleVectorStore.builder(embeddingModel).build();
	}

}
