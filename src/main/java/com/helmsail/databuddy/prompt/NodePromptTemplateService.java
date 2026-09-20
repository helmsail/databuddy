package com.helmsail.databuddy.prompt;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.helmsail.databuddy.exception.BusinessException;
import com.helmsail.databuddy.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 提示词模板服务:节点侧只读(生效版本)走 mapper.selectEffective;管理侧(列表/新增版本/激活)全在此。
 * 内容修订 = 新增一条同 name 新版本再激活(不提供原地修改:旧版本可回滚,唯一(name, version)防重复);
 * 旧行留着无害,清理直接改表。节点每次执行都取生效版本,激活即时生效、无需重启
 */
@Slf4j
@Service
public class NodePromptTemplateService {

	private final NodePromptTemplateMapper mapper;

	public NodePromptTemplateService(NodePromptTemplateMapper mapper) {
		this.mapper = mapper;
	}

	/** 全部版本(管理列表用:按 name,激活在前,版本倒序) */
	public List<NodePromptTemplate> list() {
		return mapper.selectAll();
	}

	/** 新增新版本:版本号服务端自增(同 name 最大值 + 1),默认未激活(enabled 只由 activate 滚动) */
	public NodePromptTemplate save(NodePromptTemplate template) {
		if (template == null || !StringUtils.hasText(template.getName()) || !StringUtils.hasText(template.getContent())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "name 与 content 必填");
		}
		String name = template.getName().trim();
		int version = mapper.maxVersion(name) + 1;
		NodePromptTemplate created = new NodePromptTemplate();
		created.setName(name);
		created.setContent(template.getContent());
		created.setVersion(version);
		mapper.insert(created);
		log.info("提示词新版本已入库: name={}, version={}, id={}", name, version, created.getId());
		return created;
	}

	/** 激活:同 name 激活位滚动到该行(一条语句原子完成);节点侧下次执行即用该版本 */
	public NodePromptTemplate activate(Long id) {
		NodePromptTemplate template = mapper.selectById(id);
		if (template == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "提示词版本不存在: " + id);
		}
		mapper.activate(id, template.getName());
		template.setEnabled(true);
		log.info("提示词版本已激活: name={}, version={}, id={}", template.getName(), template.getVersion(), id);
		return template;
	}

	/** 生效版本(节点侧同款读取:激活优先,均未激活回退最新);不存在抛 404 */
	public NodePromptTemplate effective(String name) {
		NodePromptTemplate template = mapper.selectEffective(name);
		if (template == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "提示词不存在: " + name);
		}
		return template;
	}

}
