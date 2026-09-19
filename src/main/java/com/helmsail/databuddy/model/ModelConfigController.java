package com.helmsail.databuddy.model;

import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置入口(唯一 Controller):新增 / 列表 / 激活(激活即热切换实例);
 * 只做 HTTP 层,业务与存储编排全在 ModelConfigService;错误统一由全局异常处理器转 ApiResponse
 */
@RestController
@RequestMapping("/model")
@CrossOrigin(origins = "*")
public class ModelConfigController {

	private final ModelConfigService modelConfigService;

	public ModelConfigController(ModelConfigService modelConfigService) {
		this.modelConfigService = modelConfigService;
	}

	/** 配置列表(同类型激活在前) */
	@GetMapping("/configs")
	public List<ModelConfig> list() {
		return modelConfigService.list();
	}

	/** 新增配置(默认未激活) */
	@PostMapping("/configs")
	public ModelConfig save(@RequestBody ModelConfig config) {
		return modelConfigService.save(config);
	}

	/** 激活配置(同类型激活位滚动;立即重建实例) */
	@PostMapping("/configs/{id}/activate")
	public ModelConfig activate(@PathVariable("id") Long id) {
		return modelConfigService.activate(id);
	}

}
