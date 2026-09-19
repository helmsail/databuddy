package com.helmsail.databuddy.prompt;

import lombok.Data;

/**
 * 节点提示词模板(node_prompt_template 表,系统库)
 */
@Data
public class NodePromptTemplate {

	private Long id;

	/** 提示词标识(对应节点) */
	private String name;

	/** 模板内容,占位符 {query} */
	private String content;

	/** 版本号,同 name 递增 */
	private Integer version;

	/** 激活标记:同 name 仅一个生效;生效版本 = 激活优先,均未激活回退最新版本 */
	private Boolean enabled;

}
