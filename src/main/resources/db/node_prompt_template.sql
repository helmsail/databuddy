-- 节点提示词模板表:启动时幂等执行(建表 IF NOT EXISTS)
-- 运行期修改提示词:直接改表即可,升级建议以新 version 插入并激活
-- enabled:1 = 激活(NULL = 未激活);唯一索引 (name, enabled) 保证同 name 至多一个激活(多 NULL 不冲突)
-- 生效版本 = 激活版本(至多一个);均未激活则回退最新版本
-- 提示词数据:节点接入时按 name/version 插入;激活 = 将目标版本 enabled 置 1、其余置 NULL(唯一索引兜底)
CREATE TABLE IF NOT EXISTS node_prompt_template (
	id BIGINT AUTO_INCREMENT PRIMARY KEY,
	name VARCHAR(128) NOT NULL,
	content TEXT NOT NULL,
	version INT NOT NULL,
	enabled TINYINT NULL,
	UNIQUE (name, version),
	UNIQUE (name, enabled)
);
