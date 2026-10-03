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
 * 临时实例(连通性测试)的构建知识同样收口本工厂,只是不进生效字段。
 * 构建的模型挂接 ObservationRegistry,LLM 调用自动接入观测(span 与 token 指标)
 */
@Slf4j
@Component
public class AiModelServiceFactory {

	private final ObservationRegistry observationRegistry;

	private volatile ChatClient chatClient;

	private volatile EmbeddingModel embeddingModel;

	/** 当前生效 EMBEDDING 模型名:随实例同生共死,供向量写入打标与检索过滤读取(数据职责,必须与实例一致) */
	private volatile String embeddingModelName;

	/** 生效实例的响应超时(读空闲):非流式长文本生成(规划 / 报告)服务端可能长时间零字节下发,受默认读超时约束会在中途抛 ReadTimeoutException(已实测),放大到 300s 覆盖长生成场景 */
	private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(300);

	/** 连通性测试响应超时:测试要快速失败,不等生效实例的 300s 长窗口 */
	private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

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

	/** 当前生效 EMBEDDING 模型名;未配置返回 null(向量打标与检索过滤据此判定分区) */
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

	/**
	 * 连通性测试:用配置构建一次性临时实例真实调用一次(临时实例不挂观测、不带调优参数、超时从短快速失败,用完即丢);
	 * 失败翻译为可读信息(鉴权 / 地址 / 额度 / 网络),抛业务异常由全局处理器输出
	 */
	public void testConnection(AiModelConfig config) {
		try {
			switch (config.getModelType()) {
				case CHAT -> testChat(config);
				case EMBEDDING -> testEmbedding(config);
			}
		}
		catch (BusinessException e) {
			throw e;
		}
		catch (RuntimeException e) {
			log.warn("模型通路测试失败: id={}, type={}, {}", config.getId(), config.getModelType(), e.getMessage());
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, parseTestError(e));
		}
		log.info("模型通路测试通过: id={}, type={}, model={}", config.getId(), config.getModelType(),
				config.getModelName());
	}

	/** 对话模型:最轻量请求探活;响应空视为不通 */
	private void testChat(AiModelConfig config) {
		ChatModel model = OpenAiChatModel.builder()
			.openAiApi(buildApi(config, TEST_RESPONSE_TIMEOUT))
			.defaultOptions(OpenAiChatOptions.builder().model(config.getModelName()).build())
			.build();
		String response = model.call("Hello");
		if (!StringUtils.hasText(response)) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模型返回内容为空");
		}
	}

	/** 嵌入模型:单条文本探活;向量空视为不通 */
	private void testEmbedding(AiModelConfig config) {
		EmbeddingModel model = new OpenAiEmbeddingModel(buildApi(config, TEST_RESPONSE_TIMEOUT), MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(), RetryUtils.DEFAULT_RETRY_TEMPLATE);
		float[] embedding = model.embed("测试");
		if (embedding == null || embedding.length == 0) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模型生成的向量为空");
		}
	}

	/** 测试异常 → 可读信息(常见状态码与网络问题逐类翻译;其余原样返回) */
	private String parseTestError(RuntimeException e) {
		String message = StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
		if (message.contains("401")) {
			return "鉴权失败(401):请检查 API Key 是否正确";
		}
		if (message.contains("404")) {
			return "接口不存在(404):请检查 baseUrl 是否配置正确";
		}
		if (message.contains("429")) {
			return "请求过多或余额不足(429):请检查厂商额度";
		}
		if (message.contains("Connection refused")) {
			return "无法连接服务地址:请检查 baseUrl 与网络";
		}
		if (message.contains("UnknownHost") || message.contains("Name or service not known")) {
			return "域名无法解析:请检查 baseUrl 拼写与网络";
		}
		if (message.toLowerCase().contains("timeout")) {
			return "连接超时:请检查服务地址与网络";
		}
		return message;
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
		ChatModel chatModel = OpenAiChatModel.builder()
			.openAiApi(buildApi(config, RESPONSE_TIMEOUT))
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
		EmbeddingModel model = new OpenAiEmbeddingModel(buildApi(config, RESPONSE_TIMEOUT), MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(),
				RetryUtils.DEFAULT_RETRY_TEMPLATE, observationRegistry); // 5 参构造:挂上观测
		log.info("EMBEDDING 模型已切换: {} ({})", config.getModelName(), config.getBaseUrl());
		return model;
	}

	/**
	 * 构建通讯对象(统一走 OpenAI 兼容协议,apiKey 为空时传空串,兼容本地无鉴权部署);
	 * 生效实例与连通性测试的临时实例共用本构建(仅超时不同:测试从短快速失败),保证"测试通过"对生效构建有参考意义
	 */
	private OpenAiApi buildApi(AiModelConfig config, Duration responseTimeout) {
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		return OpenAiApi.builder()
			.baseUrl(config.getBaseUrl())
			.apiKey(apiKey)
			.restClientBuilder(RestClient.builder().requestFactory(new ReactorClientHttpRequestFactory(httpClient(responseTimeout))))
			.webClientBuilder(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient(responseTimeout))))
			.build();
	}

	/** 模型调用 HTTP 客户端(每个连接器独立实例:内部持有各自连接池状态) */
	private static HttpClient httpClient(Duration responseTimeout) {
		return HttpClient.create()
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
			.responseTimeout(responseTimeout);
	}

}
