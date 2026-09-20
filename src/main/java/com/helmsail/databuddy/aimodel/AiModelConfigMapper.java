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
 * 模型配置 Mapper(系统库 ai_model_config 表):存储读写的唯一落点,业务语义在 AiModelConfigService
 */
@Mapper
public interface AiModelConfigMapper {

	/** 全部配置(同类型激活在前,再按 id) */
	@Select("SELECT id, model_type, model_name, base_url, api_key, temperature, max_tokens, top_p, frequency_penalty, presence_penalty, seed, is_active, create_time, update_time FROM ai_model_config ORDER BY model_type, is_active DESC, id")
	List<AiModelConfig> selectAll();

	/** 按 id 查;不存在返回 null */
	@Select("SELECT id, model_type, model_name, base_url, api_key, temperature, max_tokens, top_p, frequency_penalty, presence_penalty, seed, is_active, create_time, update_time FROM ai_model_config WHERE id = #{id}")
	AiModelConfig selectById(@Param("id") Long id);

	/** 某类型的激活行(唯一索引保证至多一个);无返回 null */
	@Select("SELECT id, model_type, model_name, base_url, api_key, temperature, max_tokens, top_p, frequency_penalty, presence_penalty, seed, is_active, create_time, update_time FROM ai_model_config WHERE model_type = #{type} AND is_active = 1")
	AiModelConfig selectActive(@Param("type") AiModelType type);

	/** 新增(默认未激活:is_active 不在此列,激活走 activate);回填自增 id */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO ai_model_config (model_type, model_name, base_url, api_key, temperature, max_tokens, top_p, frequency_penalty, presence_penalty, seed) VALUES (#{modelType}, #{modelName}, #{baseUrl}, #{apiKey}, #{temperature}, #{maxTokens}, #{topP}, #{frequencyPenalty}, #{presencePenalty}, #{seed})")
	void insert(AiModelConfig config);

	/** 激活滚动:目标行置 1、同类型其余置 NULL(一条语句原子完成) */
	@Update("UPDATE ai_model_config SET is_active = IF(id = #{id}, 1, NULL) WHERE model_type = #{type}")
	void activate(@Param("id") Long id, @Param("type") AiModelType type);

	/** 删除配置行(激活行删除 = 同时停用,运行时实例由 Service 清空) */
	@Delete("DELETE FROM ai_model_config WHERE id = #{id}")
	void deleteById(@Param("id") Long id);

	/** 修改配置行(类型不可改;apiKey 由 Service 合并后传入) */
	@Update("UPDATE ai_model_config SET model_type = #{modelType}, model_name = #{modelName}, base_url = #{baseUrl}, api_key = #{apiKey}, temperature = #{temperature}, max_tokens = #{maxTokens}, top_p = #{topP}, frequency_penalty = #{frequencyPenalty}, presence_penalty = #{presencePenalty}, seed = #{seed} WHERE id = #{id}")
	void update(AiModelConfig config);

}
