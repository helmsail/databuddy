package com.helmsail.databuddy.bizdatabase;

import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helmsail.databuddy.bizdatabase.jdbc.model.ColumnInfo;
import com.helmsail.databuddy.bizdatabase.jdbc.model.TableInfo;
import com.helmsail.databuddy.result.ApiResponse;

/**
 * 业务库模块唯一入口:配置(列表/新增/更新/删除;新增与更新会先探测真实连通)、表清单与表结构查询、
 * 表关系(列表/新增/删除);只做 HTTP 层,编排全在 BizDatabaseService;成功失败均为统一信封(见 ApiResponse)
 */
@RestController
@RequestMapping("/bizdatabase")
@CrossOrigin(origins = "*")
public class BizDatabaseController {

	private final BizDatabaseService bizDatabaseService;

	public BizDatabaseController(BizDatabaseService bizDatabaseService) {
		this.bizDatabaseService = bizDatabaseService;
	}

	/** 配置列表 */
	@GetMapping("/configs")
	public ApiResponse<List<BizDatabaseConfig>> listConfigs() {
		return ApiResponse.success(bizDatabaseService.listConfigs());
	}

	/** 新增配置(密码加密落库) */
	@PostMapping("/configs")
	public ApiResponse<BizDatabaseConfig> saveConfig(@RequestBody BizDatabaseConfig config) {
		return ApiResponse.success(bizDatabaseService.saveConfig(config));
	}

	/** 更新配置(密码留空 = 保持不变) */
	@PostMapping("/configs/{id}")
	public ApiResponse<BizDatabaseConfig> updateConfig(@PathVariable("id") Long id,
			@RequestBody BizDatabaseConfig config) {
		return ApiResponse.success(bizDatabaseService.updateConfig(id, config));
	}

	/** 删除配置(连同该库表关系与连接池) */
	@DeleteMapping("/configs/{id}")
	public ApiResponse<Void> deleteConfig(@PathVariable("id") Long id) {
		bizDatabaseService.deleteConfig(id);
		return ApiResponse.success();
	}

	/** 某库的表清单(直连实时查询) */
	@GetMapping("/configs/{id}/tables")
	public ApiResponse<List<TableInfo>> listTables(@PathVariable("id") Long id) {
		return ApiResponse.success(bizDatabaseService.listTables(id));
	}

	/** 某表的结构(直连实时查询) */
	@GetMapping("/configs/{id}/tables/{table}/columns")
	public ApiResponse<List<ColumnInfo>> listColumns(@PathVariable("id") Long id, @PathVariable("table") String table) {
		return ApiResponse.success(bizDatabaseService.listColumns(id, table));
	}

	/** 某库的关系列表 */
	@GetMapping("/relations")
	public ApiResponse<List<BizTableRelation>> listRelations(@RequestParam("databaseConfigId") Long databaseConfigId) {
		return ApiResponse.success(bizDatabaseService.listRelations(databaseConfigId));
	}

	/** 新增关系(一行 = 一条列对) */
	@PostMapping("/relations")
	public ApiResponse<BizTableRelation> saveRelation(@RequestBody BizTableRelation relation) {
		return ApiResponse.success(bizDatabaseService.saveRelation(relation));
	}

	/** 删除关系 */
	@DeleteMapping("/relations/{id}")
	public ApiResponse<Void> deleteRelation(@PathVariable("id") Long id) {
		bizDatabaseService.deleteRelation(id);
		return ApiResponse.success();
	}

}
