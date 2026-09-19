package com.helmsail.databuddy.aimodel;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClientBuilder;
import org.springframework.ai.chat.client.advisor.observation.DefaultAdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.DefaultChatClientObservationConvention;
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
 * 临时性小用途不经过本工厂,直接使用 Spring AI 构建临时实例。
 * 构建的模型挂接 ObservationRegistry,LLM 调用自动接入观测(span 与 token 指标)
 */
@Slf4j
@Component
public class AiModelServiceFactory {

	private final ObservationRegistry observationRegistry;

	private volatile ChatClient chatClient;

	private volatile EmbeddingModel embeddingModel;

	public AiModelServiceFactory(ObservationRegistry observationRegistry) {
		this.observationRegistry = observationRegistry;
	}

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
	public void refresh(AiModelConfig config) {
		validate(config);
		try {
			switch (config.getModelType()) {
				case CHAT -> this.chatClient = buildChatClient(config);
				case EMBEDDING -> this.embeddingModel = buildEmbeddingModel(config);
			}
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建模型实例失败: " + e.getMessage(), e);
		}
	}

	/** 配置基本完整性校验:保存配置(服务)与刷新实例共用(服务与工厂同包) */
	void validate(AiModelConfig config) {
		if (config.getModelType() == null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "模型类型不能为空");
		}
		if (!StringUtils.hasText(config.getBaseUrl())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "baseUrl 不能为空");
		}
		if (!StringUtils.hasText(config.getModelName())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "模型名称不能为空");
		}
	}

	private ChatClient buildChatClient(AiModelConfig config) {
		OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(config.getModelName());
		if (config.getTemperature() != null) {
			options.temperature(config.getTemperature());
		}
		if (config.getMaxTokens() != null) {
			options.maxTokens(config.getMaxTokens());
		}
		if (config.getTopP() != null) {
			options.topP(config.getTopP());
		}
		if (config.getFrequencyPenalty() != null) {
			options.frequencyPenalty(config.getFrequencyPenalty());
		}
		if (config.getPresencePenalty() != null) {
			options.presencePenalty(config.getPresencePenalty());
		}
		if (config.getSeed() != null) {
			options.seed(config.getSeed());
		}
		ChatModel chatModel = OpenAiChatModel.builder()
			.openAiApi(buildApi(config))
			.defaultOptions(options.build())
			.observationRegistry(observationRegistry) // 模型调用自动生成 span 与 token 指标
			.build();
		// ChatClient 层同样挂 registry:框架层 span 覆盖提示词组装与 Advisor 链
		ChatClient client = new DefaultChatClientBuilder(chatModel, observationRegistry,
				new DefaultChatClientObservationConvention(), new DefaultAdvisorObservationConvention()).build();
		log.info("CHAT 模型已切换: {} ({})", config.getModelName(), config.getBaseUrl());
		return client;
	}

	private EmbeddingModel buildEmbeddingModel(AiModelConfig config) {
		EmbeddingModel model = new OpenAiEmbeddingModel(buildApi(config), MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(),
				RetryUtils.DEFAULT_RETRY_TEMPLATE, observationRegistry); // 5 参构造:挂上观测
		log.info("EMBEDDING 模型已切换: {} ({})", config.getModelName(), config.getBaseUrl());
		return model;
	}

	/** 统一走 OpenAI 兼容协议,apiKey 为空时传空串(兼容本地无鉴权部署) */
	private OpenAiApi buildApi(AiModelConfig config) {
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		return OpenAiApi.builder().baseUrl(config.getBaseUrl()).apiKey(apiKey).build();
	}

}
