package com.helmsail.databuddy.aimodel;

import java.util.List;

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
 * 配置调整 = 新增一条再激活;删除走 delete(激活行删除即停用,运行时实例一并清空)。
 * 工厂侧"先构建成功、再替换"的语义保证:坏配置不会把现役实例带下水
 */
@Slf4j
@Service
public class AiModelConfigService {

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
	public AiModelConfig activate(Long id) {
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

	/** 修改配置(类型不可改;apiKey 留空 = 保留旧密钥;激活行修改后立即重建实例) */
	public AiModelConfig update(Long id, AiModelConfig patch) {
		AiModelConfig old = mapper.selectById(id);
		if (old == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在: " + id);
		}
		patch.setId(id);
		patch.setModelType(old.getModelType()); // 类型创建后不可修改
		if (!StringUtils.hasText(patch.getApiKey())) {
			patch.setApiKey(old.getApiKey());
		}
		factory.validate(patch);
		mapper.update(patch);
		AiModelConfig updated = mapper.selectById(id);
		if (Boolean.TRUE.equals(old.getIsActive())) {
			factory.refresh(updated); // 激活行:以新配置重建实例,立即生效
		}
		log.info("模型配置已修改: id={}, type={}, model={}", id, updated.getModelType(), updated.getModelName());
		return updated;
	}

	/** 删除配置:激活中的行删除 = 同时停用(清运行时实例);删除后该类型视为未配置 */
	public void delete(Long id) {
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
	public void loadOnStartup() {
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

