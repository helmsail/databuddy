package com.helmsail.databuddy.aimodel;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 模型配置服务:配置表(ai_model_config)与模型实例(工厂)之间的唯一编排——
 * 存储读写走 mapper,实例重建走工厂;激活即热切换实例,重启由启动加载恢复。
 * 配置调整 = 新增一条再激活(不做就地编辑);失活按类型置 NULL 并清实例,删除走 delete(激活行删除即停用)。
 * 连通性测试用一次性临时实例(不走工厂);工厂侧"先构建成功、再替换"保证坏配置不带体现役实例。
 * 写路径(激活/失活/删除/启动恢复)以 synchronized 串行化——均为"先库后实例"两步写,
 * 防并发管理操作时两处"最后写入者"错位(管理操作低频,串行无性能代价;save 无实例副作用、测试不写,不占锁)
 */
@Slf4j
@Service
public class AiModelConfigService {

	/** 连通性测试响应超时:测试要快速失败,不等生效实例的 300s 长窗口 */
	private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

	private final AiModelConfigMapper mapper;

	private final AiModelServiceFactory factory;

	public AiModelConfigService(AiModelConfigMapper mapper, AiModelServiceFactory factory) {
		this.mapper = mapper;
		this.factory = factory;
	}

	/** 全部配置(列表用;同类型激活在前) */
	public List<AiModelConfig> list() {
		return mapper.selectAll();
	}

	/** 新增:默认未激活(激活另走 activate);返回带 id 的配置 */
	public AiModelConfig save(AiModelConfig config) {
		factory.validate(config);
		mapper.insert(config);
		log.info("模型配置新增: id={}, type={}, model={}", config.getId(), config.getModelType(), config.getModelName());
		return config;
	}

	/** 激活:同类型激活位滚动到该行,并立即重建实例 */
	public synchronized AiModelConfig activate(Long id) {
		AiModelConfig config = mapper.selectById(id);
		if (config == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id);
		}
		mapper.activate(id, config.getModelType());
		config.setIsActive(true);
		factory.refresh(config);
		log.info("模型配置已激活: id={}, type={}, model={}", id, config.getModelType(), config.getModelName());
		return config;
	}

	/** 失活:该类型激活位置 NULL(无激活行时空转)并清运行时实例;按类型操作,幂等 */
	public synchronized void deactivate(AiModelType type) {
		mapper.deactivate(type);
		factory.clear(type);
		log.info("模型配置已失活: type={}", type);
	}

	/**
	 * 连通性测试:按 id 读配置,构建一次性临时实例真实调用一次(不走工厂:工厂只管两个生效实例;
	 * 临时实例不挂观测,用完即丢);失败翻译为可读信息(鉴权/地址/额度/网络),抛业务异常由全局处理器输出
	 */
	public void testConnection(Long id) {
		AiModelConfig config = mapper.selectById(id);
		if (config == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id);
		}
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
			log.warn("模型通路测试失败: id={}, type={}, {}", id, config.getModelType(), e.getMessage());
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, parseTestError(e));
		}
		log.info("模型通路测试通过: id={}, type={}, model={}", id, config.getModelType(), config.getModelName());
	}

	/** 对话模型:最轻量请求探活;响应空视为不通 */
	private void testChat(AiModelConfig config) {
		ChatModel model = OpenAiChatModel.builder()
			.openAiApi(factory.buildApi(config, TEST_RESPONSE_TIMEOUT))
			.defaultOptions(OpenAiChatOptions.builder().model(config.getModelName()).build())
			.build();
		String response = model.call("Hello");
		if (!StringUtils.hasText(response)) {
			throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模型返回内容为空");
		}
	}

	/** 嵌入模型:单条文本探活;向量空视为不通 */
	private void testEmbedding(AiModelConfig config) {
		EmbeddingModel model = new OpenAiEmbeddingModel(factory.buildApi(config, TEST_RESPONSE_TIMEOUT),
				MetadataMode.EMBED, OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(),
				RetryUtils.DEFAULT_RETRY_TEMPLATE);
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

	/** 删除配置:激活中的行删除 = 同时停用(清运行时实例);删除后该类型视为未配置 */
	public synchronized void delete(Long id) {
		AiModelConfig config = mapper.selectById(id);
		if (config == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id);
		}
		mapper.deleteById(id);
		if (Boolean.TRUE.equals(config.getIsActive())) {
			factory.clear(config.getModelType());
			log.info("模型配置已删除(原为激活行,已停用): id={}, type={}, model={}", id, config.getModelType(),
					config.getModelName());
			return;
		}
		log.info("模型配置已删除: id={}, type={}, model={}", id, config.getModelType(), config.getModelName());
	}

	/** 启动加载:两种类型各取激活行重建实例;坏配置只记日志,不拦启动 */
	@EventListener(ApplicationReadyEvent.class)
	public synchronized void loadOnStartup() {
		for (AiModelType type : AiModelType.values()) {
			AiModelConfig config = mapper.selectActive(type);
			if (config == null) {
				log.info("启动加载: {} 暂无激活配置", type);
				continue;
			}
			try {
				factory.refresh(config);
			}
			catch (Exception e) {
				log.error("启动加载模型配置失败: type={}, id={}", type, config.getId(), e);
			}
		}
	}

}

