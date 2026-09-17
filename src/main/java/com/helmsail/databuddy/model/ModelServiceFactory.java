package com.helmsail.databuddy.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 模型服务工厂:同时生效的 CHAT 与 EMBEDDING 各一个实例。
 * 外部传入配置后先构建新实例、成功再替换(坏配置不影响现役实例,旧实例随引用丢弃由 GC 回收);
 * 阻塞调用与流式调用均由 Spring AI 封装,本工厂不做二次加工。
 * 临时性小用途不经过本工厂,直接使用 Spring AI 构建临时实例
 */
@Slf4j
@Component
public class ModelServiceFactory {

	private volatile ChatClient chatClient;

	private volatile EmbeddingModel embeddingModel;

	/** 获取当前生效的 CHAT 客户端;未配置时抛业务异常 */
	public ChatClient getChatClient() {
		ChatClient current = chatClient;
		if (current == null) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "未配置可用的 CHAT 模型");
		}
		return current;
	}

	/** 获取当前生效的 EMBEDDING 模型;未配置时抛业务异常 */
	public EmbeddingModel getEmbeddingModel() {
		EmbeddingModel current = embeddingModel;
		if (current == null) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "未配置可用的 EMBEDDING 模型");
		}
		return current;
	}

	/** 按配置重建对应类型的实例并替换生效 */
	public void refresh(ModelConfig config) {
		check(config);
		try {
			switch (config.getType()) {
				case CHAT -> this.chatClient = buildChatClient(config);
				case EMBEDDING -> this.embeddingModel = buildEmbeddingModel(config);
			}
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建模型实例失败: " + e.getMessage(), e);
		}
	}

	/** 配置基本完整性校验 */
	private void check(ModelConfig config) {
		if (config.getType() == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "模型类型不能为空");
		}
		if (!StringUtils.hasText(config.getBaseUrl())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "baseUrl 不能为空");
		}
		if (!StringUtils.hasText(config.getModelName())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "模型名称不能为空");
		}
	}

	private ChatClient buildChatClient(ModelConfig config) {
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(config.getModelName());
		if (config.getTemperature() != null) {
			options.temperature(config.getTemperature());
		}
		if (config.getMaxTokens() != null) {
			options.maxTokens(config.getMaxTokens());
		}
		ChatModel chatModel = OpenAiChatModel.builder()
			.openAiApi(buildApi(config))
			.defaultOptions(options.build())
			.build();
		ChatClient client = ChatClient.builder(chatModel).build();
		log.info("CHAT 模型已切换: {} ({})", config.getModelName(), config.getBaseUrl());
		return client;
	}

	private EmbeddingModel buildEmbeddingModel(ModelConfig config) {
		EmbeddingModel model = new OpenAiEmbeddingModel(buildApi(config), MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(),
				RetryUtils.DEFAULT_RETRY_TEMPLATE);
		log.info("EMBEDDING 模型已切换: {} ({})", config.getModelName(), config.getBaseUrl());
		return model;
	}

	/** 统一走 OpenAI 兼容协议,apiKey 为空时传空串(兼容本地无鉴权部署) */
	private OpenAiApi buildApi(ModelConfig config) {
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		return OpenAiApi.builder().baseUrl(config.getBaseUrl()).apiKey(apiKey).build();
	}

}
