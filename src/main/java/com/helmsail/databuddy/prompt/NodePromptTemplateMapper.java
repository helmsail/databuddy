package com.helmsail.databuddy.prompt;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 节点提示词 Mapper(系统库):节点侧读取生效版本;管理侧(列表/新增版本/激活)由 NodePromptTemplateService 编排
 */
@Mapper
public interface NodePromptTemplateMapper {

	/** 取生效版本:激活版本优先(至多一个),均未激活回退最新版本;不存在返回 null */
	@Select("SELECT id, name, content, version, enabled FROM node_prompt_template WHERE name = #{name} ORDER BY enabled DESC, version DESC LIMIT 1")
	NodePromptTemplate selectEffective(@Param("name") String name);

	/** 全部版本(管理列表用:按 name,激活在前,版本倒序) */
	@Select("SELECT id, name, content, version, enabled FROM node_prompt_template ORDER BY name, enabled DESC, version DESC")
	List<NodePromptTemplate> selectAll();

	/** 按 id 查;不存在返回 null */
	@Select("SELECT id, name, content, version, enabled FROM node_prompt_template WHERE id = #{id}")
	NodePromptTemplate selectById(@Param("id") Long id);

	/** 同 name 当前最大版本号(无版本返回 0;新增版本 = 该值 + 1) */
	@Select("SELECT COALESCE(MAX(version), 0) FROM node_prompt_template WHERE name = #{name}")
	int maxVersion(@Param("name") String name);

	/** 新增(默认未激活:enabled 不在此列,激活走 activate);回填自增 id */
	@Options(useGeneratedKeys = true, keyProperty = "id")
	@Insert("INSERT INTO node_prompt_template (name, content, version) VALUES (#{name}, #{content}, #{version})")
	void insert(NodePromptTemplate template);

	/** 激活滚动:目标行置 1、同 name 其余置 NULL(一条语句原子完成) */
	@Update("UPDATE node_prompt_template SET enabled = IF(id = #{id}, 1, NULL) WHERE name = #{name}")
	void activate(@Param("id") Long id, @Param("name") String name);

}
