package com.helmsail.databuddy.aimodel;

import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.result.ApiResponse;

/**
 * 模型配置入口(唯一 Controller):新增 / 列表 / 激活(激活即热切换实例)/ 失活(按类型停用)/ 连通性测试 / 删除(激活行删除即停用);
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

	/** 失活配置(按类型:该类型激活位置 NULL,实例清空,变为未配置;无激活行时空转,幂等) */
	@PostMapping("/configs/deactivate")
	public ApiResponse<Void> deactivate(@RequestParam("type") String type) {
		aiModelConfigService.deactivate(AiModelType.from(type));
		return ApiResponse.success();
	}

	/** 测试模型通路(按 id 读配置,一次性临时实例真实调用;成功即模型可用,失败返回可读原因) */
	@PostMapping("/configs/{id}/test")
	public ApiResponse<Void> test(@PathVariable("id") Long id) {
		aiModelConfigService.testConnection(id);
		return ApiResponse.success();
	}

	/** 删除配置(激活中的行删除 = 同时停用;该类型随之视为未配置,不再需要单独"关闭"动作) */
	@DeleteMapping("/configs/{id}")
	public ApiResponse<Void> delete(@PathVariable("id") Long id) {
		aiModelConfigService.delete(id);
		return ApiResponse.success();
	}

}
