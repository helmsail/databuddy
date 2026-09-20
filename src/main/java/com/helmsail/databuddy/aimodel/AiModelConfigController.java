package com.helmsail.databuddy.aimodel;

import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.result.ApiResponse;

/**
 * 模型配置入口(唯一 Controller):新增 / 列表 / 激活(激活即热切换实例);
 * 只做 HTTP 层,业务与存储编排全在 AiModelConfigService;成功失败均为统一信封(见 ApiResponse)
 */
@RestController
@RequestMapping("/aimodel")
@CrossOrigin(origins = "*")
public class AiModelConfigController {

	private final AiModelConfigService aiModelConfigService;

	public AiModelConfigController(AiModelConfigService aiModelConfigService) {
		this.aiModelConfigService = aiModelConfigService;
	}

	/** 配置列表(同类型激活在前) */
	@GetMapping("/configs")
	public ApiResponse<List<AiModelConfig>> list() {
		return ApiResponse.success(aiModelConfigService.list());
	}

	/** 新增配置(默认未激活) */
	@PostMapping("/configs")
	public ApiResponse<AiModelConfig> save(@RequestBody AiModelConfig config) {
		return ApiResponse.success(aiModelConfigService.save(config));
	}

	/** 激活配置(同类型激活位滚动;立即重建实例) */
	@PostMapping("/configs/{id}/activate")
	public ApiResponse<AiModelConfig> activate(@PathVariable("id") Long id) {
		return ApiResponse.success(aiModelConfigService.activate(id));
	}

}
