package com.helmsail.databuddy.aimodel;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 模型配置 Mapper(系统库 ai_model_config 表):存储读写的唯一落点,业务语义在 AiModelConfigService。
 * 方法按 增 / 查 / 改 / 删 / 激活 / 失活 排列;连接身份字段(类型 / 模型名 / 地址 / 密钥)不可改,调整走复制新建,
 * 仅调优字段(温度 / 最大 token / topP)可就地更新;
 * 激活滚动与失活均为单条 UPDATE 原子完成,叠加表上唯一索引 (model_type, is_active) 兜底,
 * 并发管理操作不会出现同类型双激活
 */
@Mapper
public interface AiModelConfigMapper {

	/** 全列清单:三处查询共用,加列只改这一处 */
	String ALL_COLUMNS = "id, model_type, model_name, base_url, api_key, temperature, max_tokens, top_p, is_active, create_time, update_time";

	// ============ 增 ============

	/** 新增(默认未激活:is_active 不在此列,激活走 activate);回填自增 id */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO ai_model_config (model_type, model_name, base_url, api_key, temperature, max_tokens, top_p) VALUES (#{modelType}, #{modelName}, #{baseUrl}, #{apiKey}, #{temperature}, #{maxTokens}, #{topP})")
	void insert(AiModelConfig config);

	// ============ 查 ============

	/** 全部配置(同类型激活在前,再按 id) */
	@Select("SELECT " + ALL_COLUMNS + " FROM ai_model_config ORDER BY model_type, is_active DESC, id")
	List<AiModelConfig> selectAll();

	/** 按 id 查;不存在返回 null */
	@Select("SELECT " + ALL_COLUMNS + " FROM ai_model_config WHERE id = #{id}")
	AiModelConfig selectById(@Param("id") Long id);

	/** 某类型的激活行(唯一索引保证至多一个);无返回 null */
	@Select("SELECT " + ALL_COLUMNS + " FROM ai_model_config WHERE model_type = #{type} AND is_active = 1")
	AiModelConfig selectActive(@Param("type") AiModelType type);

	// ============ 改(仅调优字段) ============

	/** 更新调优字段(仅 temperature / max_tokens / top_p;连接身份字段不可改,调整走复制新建) */
	@Update("UPDATE ai_model_config SET temperature = #{temperature}, max_tokens = #{maxTokens}, top_p = #{topP} WHERE id = #{id}")
	void updateTuning(AiModelConfig config);

	// ============ 删 ============

	/** 删除配置行(激活行删除 = 同时停用,运行时实例由 Service 清空) */
	@Delete("DELETE FROM ai_model_config WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

	// ============ 激活 / 失活 ============

	/** 激活滚动:目标行置 1、同类型其余置 NULL(一条语句原子完成,防"先清后置"的并发空窗) */
	@Update("UPDATE ai_model_config SET is_active = CASE WHEN id = #{id} THEN 1 ELSE NULL END WHERE model_type = #{type}")
	void activate(@Param("id") Long id, @Param("type") AiModelType type);

	/** 失活:目标行激活位置 NULL(按 id 寻址,只动激活行,已非激活时影响 0 行);对应类型由 Service 从行内读取 */
	@Update("UPDATE ai_model_config SET is_active = NULL WHERE id = #{id} AND is_active = 1")
	void deactivate(@Param("id") Long id);

}
