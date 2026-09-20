package com.helmsail.databuddy.aimodel;

import java.time.Duration;

import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;

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
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

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

	/** 当前生效 EMBEDDING 模型名(写入向量 metadata,作为模型切换后的重建依据;未配置为 null) */
	private volatile String embeddingModelName;

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

	/** 当前生效 EMBEDDING 模型名;未配置返回 null(供观测与向量重建判断,自身不抛错) */
	public String getEmbeddingModelName() {
		return embeddingModelName;
	}

	/** 按配置重建对应类型的实例并替换生效 */
	public void refresh(AiModelConfig config) {
		validate(config);
		try {
			switch (config.getModelType()) {
				case CHAT -> this.chatClient = buildChatClient(config);
				case EMBEDDING -> {
					this.embeddingModel = buildEmbeddingModel(config);
					this.embeddingModelName = config.getModelName();
				}
			}
		}
		catch (Exception e) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建模型实例失败: " + e.getMessage(), e);
		}
	}

	/** 清空某类型的运行时实例(删除激活配置时调用):置空即视为未配置,调用方将得到"未配置可用的模型" */
	public void clear(AiModelType type) {
		switch (type) {
			case CHAT -> this.chatClient = null;
			case EMBEDDING -> {
				this.embeddingModel = null;
				this.embeddingModelName = null;
			}
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

	/**
	 * 统一走 OpenAI 兼容协议,apiKey 为空时传空串(兼容本地无鉴权部署);
	 * 超时策略:连接 10s 快速失败;响应(读空闲)放大到 300s——非流式长文本生成(规划 / 报告)服务端可能长时间零字节下发,
	 * 受默认读超时约束会在中途抛 ReadTimeoutException(已实测),放大后覆盖长生成场景
	 */
	private OpenAiApi buildApi(AiModelConfig config) {
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		return OpenAiApi.builder()
			.baseUrl(config.getBaseUrl())
			.apiKey(apiKey)
			.restClientBuilder(RestClient.builder().requestFactory(new ReactorClientHttpRequestFactory(httpClient())))
			.webClientBuilder(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient())))
			.build();
	}

	/** 模型调用 HTTP 客户端(每个连接器独立实例:内部持有各自连接池状态) */
	private static HttpClient httpClient() {
		return HttpClient.create()
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
			.responseTimeout(Duration.ofSeconds(300));
	}

}
