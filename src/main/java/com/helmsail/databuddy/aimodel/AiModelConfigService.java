package com.helmsail.databuddy.aimodel;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 模型配置服务:配置表(ai_model_config)与模型实例(工厂)之间的唯一编排——
 * 存储读写走 mapper,实例重建走工厂;激活即热切换实例,重启由启动加载恢复。
 * 连接身份字段调整 = 新增一条再激活(不做就地编辑),调优字段(温度 / 最大 token / topP)可就地更新;
 * 失活按 id 置 NULL 并清实例,删除走 delete(激活行删除即停用)。
 * 连通性测试:配置读取留本层(存储依赖),临时实例的构建与判定归工厂(构建知识不出工厂);
 * 工厂侧"先构建成功、再替换"保证坏配置不带体现役实例。
 * 写路径(更新/激活/失活/删除/启动恢复)以 synchronized 串行化——均为"先库后实例"两步写,
 * 防并发管理操作时两处"最后写入者"错位(管理操作低频,串行无性能代价;save 无实例副作用、测试不写,不占锁)
 */
@Slf4j
@Service
public class AiModelConfigService {

	private final AiModelConfigMapper mapper;

	private final AiModelServiceFactory factory;

	private final ApplicationEventPublisher eventPublisher;

	public AiModelConfigService(AiModelConfigMapper mapper, AiModelServiceFactory factory,
			ApplicationEventPublisher eventPublisher) {
		this.mapper = mapper;
		this.factory = factory;
		this.eventPublisher = eventPublisher;
	}

	/** 全部配置(列表用;同类型激活在前) */
	public List<AiModelConfig> list() {
		return mapper.selectAll();
	}

	/** 全部 EMBEDDING 配置的模型名(含未激活备用行;删除配置后判定该模型名是否还有行——没有则提示其向量分区待回收) */
	public List<String> embeddingModelNames() {
		return mapper.selectAll().stream()
			.filter(config -> config.getModelType() == AiModelType.EMBEDDING)
			.map(AiModelConfig::getModelName)
			.distinct()
			.toList();
	}

	/** 新增:默认未激活(激活另走 activate);返回带 id 的配置 */
	public AiModelConfig save(AiModelConfig config) {
		factory.validate(config);
		mapper.insert(config);
		log.info("模型配置新增: id={}, type={}, model={}", config.getId(), config.getModelType(), config.getModelName());
		return config;
	}

	/** 调优:仅 temperature / max_tokens / top_p 可就地更新(连接身份字段保持不可改);激活行更新后重建实例使新参数立即生效 */
	public synchronized AiModelConfig updateTuning(Long id, AiModelConfig tuning) {
		AiModelConfig config = mapper.selectById(id);
		if (config == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id);
		}
		config.setTemperature(tuning.getTemperature());
		config.setMaxTokens(tuning.getMaxTokens());
		config.setTopP(tuning.getTopP());
		mapper.updateTuning(config);
		if (Boolean.TRUE.equals(config.getIsActive())) {
			factory.refresh(config);
		}
		log.info("模型配置调优已更新: id={}, type={}, temperature={}, maxTokens={}, topP={}", id, config.getModelType(),
				config.getTemperature(), config.getMaxTokens(), config.getTopP());
		return config;
	}

	/** 激活:同类型激活位滚动到该行,并立即重建实例;EMBEDDING 换模型时发布切换事件(知识侧标记旧向量失效,重试管线在新分区重建;旧分区保留,切回即恢复) */
	public synchronized AiModelConfig activate(Long id) {
		AiModelConfig config = mapper.selectById(id);
		if (config == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id);
		}
		String previousName = config.getModelType() == AiModelType.EMBEDDING
				? factory.getEmbeddingModelName() : null;
		mapper.activate(id, config.getModelType());
		config.setIsActive(true);
		factory.refresh(config);
		if (config.getModelType() == AiModelType.EMBEDDING && !Objects.equals(previousName, config.getModelName())) {
			log.info("EMBEDDING 模型已切换: {} -> {};知识向量标记失效待重建(旧模型向量按分区保留,切回即恢复)",
					previousName, config.getModelName());
			eventPublisher.publishEvent(new EmbeddingModelSwitchedEvent(previousName, config.getModelName()));
		}
		log.info("模型配置已激活: id={}, type={}, model={}", id, config.getModelType(), config.getModelName());
		return config;
	}

	/** 失活:目标行激活位置 NULL 并清对应类型运行时实例;按 id 寻址,非激活行空转,幂等 */
	public synchronized void deactivate(Long id) {
		AiModelConfig config = mapper.selectById(id);
		if (config == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id);
		}
		mapper.deactivate(id);
		if (Boolean.TRUE.equals(config.getIsActive())) {
			factory.clear(config.getModelType());
			log.info("模型配置已失活: id={}, type={}", id, config.getModelType());
		}
	}

	/** 连通性测试:按 id 读配置(存储依赖留在本层);临时实例构建与判定全部由工厂执行(构建知识不出工厂) */
	public void testConnection(Long id) {
		AiModelConfig config = mapper.selectById(id);
		if (config == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id);
		}
		factory.testConnection(config);
	}

	/** 删除配置:激活中的行删除 = 同时停用(清运行时实例);EMBEDDING 最后一行删除 = 其向量分区待手动回收(向量运维台删除;重添配置即可保住) */
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
		}
		else {
			log.info("模型配置已删除: id={}, type={}, model={}", id, config.getModelType(), config.getModelName());
		}
		if (config.getModelType() == AiModelType.EMBEDDING && !embeddingModelNames().contains(config.getModelName())) {
			log.info("模型 {} 已无配置;其向量分区待手动回收(向量运维台;如需保留,重新添加同名配置即可)", config.getModelName());
		}
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

