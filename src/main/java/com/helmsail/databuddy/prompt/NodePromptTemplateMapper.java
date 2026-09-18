package com.helmsail.databuddy.prompt;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 节点提示词 Mapper(系统库):节点侧读取生效版本的入口;版本数据的增改与激活由数据库直接维护
 */
@Mapper
public interface NodePromptTemplateMapper {

	/** 取生效版本:激活版本优先(至多一个),均未激活回退最新版本;不存在返回 null */
	@Select("SELECT id, name, content, version, enabled FROM node_prompt_template WHERE name = #{name} ORDER BY enabled DESC, version DESC LIMIT 1")
	NodePromptTemplate selectEffective(@Param("name") String name);

}
