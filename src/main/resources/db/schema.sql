-- 系统库初始化脚本:启动幂等执行(建表 IF NOT EXISTS,可反复跑)
-- 组织约定:表结构(DDL)统一放前面,初始化数据(种子 INSERT)统一放最后
-- 内容:节点提示词模板(node_prompt_template)、会话记忆(session_memory)、模型配置(model_config)

-- ============ 表结构 ============

-- 节点提示词模板:运行期修改直接改表;升级建议以新 version 插入并激活
-- enabled:1 = 激活(NULL = 未激活);唯一索引 (name, enabled) 保证同 name 至多一个激活(多 NULL 不冲突)
-- 生效版本 = 激活版本(至多一个);均未激活则回退最新版本
CREATE TABLE IF NOT EXISTS node_prompt_template (
	id BIGINT AUTO_INCREMENT PRIMARY KEY,
	name VARCHAR(128) NOT NULL,
	content TEXT NOT NULL,
	version INT NOT NULL,
	enabled TINYINT NULL,
	UNIQUE (name, version),
	UNIQUE (name, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话记忆:该会话的"记忆条目流";kind='turn' 原文轮(一行一轮) / kind='summary' 压缩条目(一行一段,浓缩掉的老轮)
-- 读序约定:最新一条 summary + 最近 N 条 turn 拼成上下文;压缩写序:先插新 summary、再删旧 summary 与溢出行
CREATE TABLE IF NOT EXISTS session_memory (
	id          BIGINT AUTO_INCREMENT PRIMARY KEY,
	session_id  VARCHAR(64)  NOT NULL,
	kind        VARCHAR(16)  NOT NULL,
	question    TEXT         NULL,
	answer      TEXT         NOT NULL,
	create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
	INDEX idx_session (session_id, kind, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 模型配置:OpenAI 兼容协议;同时生效的 CHAT 与 EMBEDDING 各一个
-- is_active:1 = 激活(NULL = 未激活);唯一索引 (model_type, is_active) 保证同类型至多一个激活(多 NULL 不冲突)
-- 调优参数可空 = 用服务商默认,仅 CHAT 用(EMBEDDING 行留 NULL);"停用保留" = is_active 置 NULL,彻底不要 = 物理删除
CREATE TABLE IF NOT EXISTS model_config (
	id                BIGINT AUTO_INCREMENT PRIMARY KEY,
	model_type        VARCHAR(16)  NOT NULL,
	model_name        VARCHAR(128) NOT NULL,
	base_url          VARCHAR(256) NOT NULL,
	api_key           VARCHAR(256) NULL,
	temperature       DECIMAL(3,2) NULL,
	max_tokens        INT          NULL,
	top_p             DECIMAL(3,2) NULL,
	frequency_penalty DECIMAL(3,2) NULL,
	presence_penalty  DECIMAL(3,2) NULL,
	seed              INT          NULL,
	is_active         TINYINT      NULL,
	create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	UNIQUE (model_type, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 初始化数据(种子) ============

-- 节点提示词:新增提示词按 name/version 插入;激活 = 将目标版本 enabled 置 1、其余置 NULL
INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'intent-recognition',
'你是意图识别助手,判断用户输入属于哪一类:

- data_analysis:数据查询、统计分析、报表、指标、趋势相关的问题
- chat:闲聊、问候或与数据分析无关的请求

用户输入:{query}

要求:仅输出 JSON,不要输出其他内容;classification 只能是 data_analysis 或 chat;若为 chat,response 给出友好简短的回复;若为 data_analysis,response 为空字符串。',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'intent-recognition');
