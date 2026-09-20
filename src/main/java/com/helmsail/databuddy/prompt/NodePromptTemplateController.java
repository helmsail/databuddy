package com.helmsail.databuddy.prompt;

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
 * 提示词入口(唯一 Controller):版本列表 / 新增版本 / 激活 / 查生效;
 * 只做 HTTP 层,业务与编排全在 NodePromptTemplateService;成功失败均为统一信封(见 ApiResponse)
 */
@RestController
@RequestMapping("/prompt")
@CrossOrigin(origins = "*")
public class NodePromptTemplateController {

	private final NodePromptTemplateService promptTemplateService;

	public NodePromptTemplateController(NodePromptTemplateService promptTemplateService) {
		this.promptTemplateService = promptTemplateService;
	}

	/** 版本列表(按 name,激活在前,版本倒序;含全部版本的完整内容) */
	@GetMapping("/templates")
	public ApiResponse<List<NodePromptTemplate>> list() {
		return ApiResponse.success(promptTemplateService.list());
	}

	/** 新增版本(默认未激活;版本号服务端自增,body 只需 name 与 content) */
	@PostMapping("/templates")
	public ApiResponse<NodePromptTemplate> save(@RequestBody NodePromptTemplate template) {
		return ApiResponse.success(promptTemplateService.save(template));
	}

	/** 激活版本(同 name 激活位滚动;节点侧下次执行即生效) */
	@PostMapping("/templates/{id}/activate")
	public ApiResponse<NodePromptTemplate> activate(@PathVariable("id") Long id) {
		return ApiResponse.success(promptTemplateService.activate(id));
	}

	/** 查某 name 的生效版本(激活优先;均未激活回退最新) */
	@GetMapping("/templates/{name}/effective")
	public ApiResponse<NodePromptTemplate> effective(@PathVariable("name") String name) {
		return ApiResponse.success(promptTemplateService.effective(name));
	}

}
