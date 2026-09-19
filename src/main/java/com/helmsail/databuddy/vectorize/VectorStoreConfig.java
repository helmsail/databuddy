package com.helmsail.databuddy.vectorize;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.helmsail.databuddy.aimodel.AiModelServiceFactory;

/**
 * 向量存储装配:当前用内存实现(SimpleVectorStore,重启丢向量、数据可重建);
 * 后续选定真实向量库 = 换对应 starter + 只改这一个 bean,业务代码零改动
 */
@Configuration
public class VectorStoreConfig {

	@Bean
	public VectorStore vectorStore(AiModelServiceFactory aiModelServiceFactory) {
		// 刻意手动 new、不注册为 bean:容器里不出现 EmbeddingModel 类型,避免与未来的外部嵌入 bean 冲突
		EmbeddingModel embeddingModel = new DelegatingEmbeddingModel(aiModelServiceFactory);
		return SimpleVectorStore.builder(embeddingModel).build();
	}

}
