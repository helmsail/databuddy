-- 系统库初始化脚本:启动幂等执行(建表 IF NOT EXISTS,可反复跑)
-- 组织约定:表结构(DDL)统一放前面,初始化数据(种子 INSERT)统一放最后
-- 内容:节点提示词模板(node_prompt_template)、会话记忆(session_memory)、模型配置(ai_model_config)、业务库配置(biz_database_config)与表级关联(biz_table_relation)、智能体(agent)与表绑定(agent_biz_table)、业务术语(agent_biz_term)、业务问答(agent_biz_qa)、业务文档(agent_biz_document)、会话(session)与会话消息(session_message)

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
CREATE TABLE IF NOT EXISTS ai_model_config (
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

-- 业务库配置:一行 = 一个可分析的库(粒度直接到库,connection_url 自带库名)
-- password:AES-256-GCM 密文(明文只在 API 出入);密钥见 databuddy.crypto.aes-key
CREATE TABLE IF NOT EXISTS biz_database_config (
	id             BIGINT AUTO_INCREMENT PRIMARY KEY,
	name           VARCHAR(128) NOT NULL,
	db_type        VARCHAR(16)  NOT NULL,
	username       VARCHAR(128) NOT NULL,
	password       VARCHAR(256) NULL,
	connection_url VARCHAR(512) NOT NULL,
	description    VARCHAR(256) NULL,
	create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 表级关联:一行 = 一条列对关联(source_table.source_column → target_table.target_column)
-- relation_type 数量关系(方向 source → target):ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY
CREATE TABLE IF NOT EXISTS biz_table_relation (
	id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
	database_config_id BIGINT       NOT NULL,
	source_table_name  VARCHAR(128) NOT NULL,
	source_column_name VARCHAR(128) NOT NULL,
	target_table_name  VARCHAR(128) NOT NULL,
	target_column_name VARCHAR(128) NOT NULL,
	relation_type      VARCHAR(16)  NOT NULL,
	description        VARCHAR(256) NULL,
	create_time        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	INDEX idx_database (database_config_id, source_table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 智能体:身份/注册表(一行 = 一个智能体);模型、业务库、提示词等绑定关系后续按需接入,不预埋字段
CREATE TABLE IF NOT EXISTS agent (
	id          BIGINT AUTO_INCREMENT PRIMARY KEY,
	name        VARCHAR(128) NOT NULL,
	description VARCHAR(256) NULL,
	create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- agent 表绑定:一行 = agent 绑定的一张业务表;向量化粒度整表一块(表名 + 表注释 + 列注释,批量整体同步)
-- embedding_status:PENDING 待向量化 / SYNCED 已同步 / FAILED 失败待重试(agent_biz_* 同值域);error_msg 存最近一次失败原因
CREATE TABLE IF NOT EXISTS agent_biz_table (
	id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
	agent_id           BIGINT       NOT NULL,
	database_config_id BIGINT       NOT NULL,
	table_name         VARCHAR(128) NOT NULL,
	embedding_status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
	error_msg          VARCHAR(512) NULL,
	create_time        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	UNIQUE (agent_id, database_config_id, table_name),
	INDEX idx_agent_status (agent_id, embedding_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- agent 业务术语:一行 = 一个术语;向量化内容 = 术语 + 释义,单条同步(CRUD 即触发)
-- synonyms:同义词/别名,英文逗号分隔(如:成交额,销售额);embedding_status:PENDING / SYNCED / FAILED;error_msg 存最近一次失败原因
CREATE TABLE IF NOT EXISTS agent_biz_term (
	id               BIGINT AUTO_INCREMENT PRIMARY KEY,
	agent_id         BIGINT        NOT NULL,
	business_term    VARCHAR(128)  NOT NULL,
	synonyms         VARCHAR(512)  NULL,
	description      VARCHAR(1024) NULL,
	embedding_status VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
	error_msg        VARCHAR(512)  NULL,
	create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	UNIQUE (agent_id, business_term),
	INDEX idx_agent_status (agent_id, embedding_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- agent 业务问答:一行 = 一组问答;仅问题做同步向量化,答案(content)留库回源,CRUD 即触发
-- embedding_status:PENDING / SYNCED / FAILED;error_msg 存最近一次失败原因
CREATE TABLE IF NOT EXISTS agent_biz_qa (
	id               BIGINT AUTO_INCREMENT PRIMARY KEY,
	agent_id         BIGINT       NOT NULL,
	question         VARCHAR(512) NOT NULL,
	content          TEXT         NULL,
	embedding_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
	error_msg        VARCHAR(512) NULL,
	create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	UNIQUE (agent_id, question),
	INDEX idx_agent_status (agent_id, embedding_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- agent 业务文档:一行 = 一份文档;文件本体在 storage 包(本地存储),文本按 splitter_type 切分向量化
-- 文本获取:markdown 直读保结构,其余格式经 Tika 提取(自动编码识别 / 去 HTML 标签)
-- 上传异步处理(落行 PENDING → worker 后台切分入向量,失败进兜底重试);扩展名白名单:文本类 + pdf/word/excel/ppt 等常见格式,其余上传即拒
-- storage_type:存储分发键(当前 LOCAL);UNIQUE (agent_id, name):同 agent 下文档名唯一(文件按 agent 目录 + 文件名落盘)
-- splitter_type:切分策略(WHOLE / PARAGRAPH / MARKDOWN / TOKEN);embedding_status:PENDING / SYNCED / FAILED
CREATE TABLE IF NOT EXISTS agent_biz_document (
	id               BIGINT AUTO_INCREMENT PRIMARY KEY,
	agent_id         BIGINT       NOT NULL,
	name             VARCHAR(256) NOT NULL,
	storage_type     VARCHAR(16)  NOT NULL DEFAULT 'LOCAL',
	storage_path     VARCHAR(512) NOT NULL,
	splitter_type    VARCHAR(16)  NOT NULL DEFAULT 'PARAGRAPH',
	embedding_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
	error_msg        VARCHAR(512) NULL,
	create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	UNIQUE (agent_id, name),
	INDEX idx_agent_status (agent_id, embedding_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话(用户侧历史:一行 = 一次连续对话;主键即会话 UUID;纯 CRUD,与图零耦合)
-- 历史写入由客户端编排(存 user → 跑图 → 收尾存 assistant);删除为硬删(级联清消息);图侧清理由客户端调 /graph 接口
CREATE TABLE IF NOT EXISTS session (
	id          VARCHAR(36)  NOT NULL,
	agent_id    BIGINT       NOT NULL,
	title       VARCHAR(128) NULL,
	create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	PRIMARY KEY (id),
	INDEX idx_agent (agent_id, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话消息(无损全文;role = USER / ASSISTANT;message_type 起步 text,留扩展位;随会话硬删而清理)
CREATE TABLE IF NOT EXISTS session_message (
	id           BIGINT AUTO_INCREMENT PRIMARY KEY,
	session_id   VARCHAR(36) NOT NULL,
	role         VARCHAR(16) NOT NULL,
	content      MEDIUMTEXT  NOT NULL,
	message_type VARCHAR(32) NOT NULL DEFAULT 'text',
	create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
	INDEX idx_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 初始化数据(种子) ============

-- 节点提示词:新增提示词按 name/version 插入;激活 = 将目标版本 enabled 置 1、其余置 NULL
INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'intent-recognition',
'你是数据分析工作流最前端的意图分类器:快速判定用户最新输入是“闲聊或无关指令”还是“可能的数据分析请求”,过滤明显无效的请求、节约后续计算。

判定原则(极端保守,宁放过不杀错):只要输入有一丝可能是想查询或分析数据,一律归为可能的数据分析请求(data_analysis);仅当明确无疑地是闲聊或与数据无关时,才归为闲聊(chat)。

【对话历史】
{history}

【最新用户输入】
{query}

判定标准:
- chat(闲聊或无关指令):纯情感或礼貌用语(如“哈哈哈”“谢谢你”);关于 AI 自身的元问题(如“你是谁”);与数据完全无关的请求(如“帮我写一首诗”“今天天气怎么样”);无意义乱码。
- data_analysis(可能的数据分析请求):含数据关键词(查询/分析/统计/排名/对比/平均值等);含业务名词或指标(如“销售额”“xx部门”“那个员工”);多轮中的指代与追问(如“那个呢”“他们呢”“具体一点”);口语化但实质是查数据(如“我们公司哪个产品卖得最好”)。

示例:
1) 历史(无),输入“你好” → {"classification": "chat", "response": "你好!我可以帮你查询、统计和分析已连接的数据。"}
2) 历史(在聊员工工资),输入“哈哈哈哈,太棒了!” → {"classification": "chat", "response": "不客气!我可以继续帮你查询和分析已连接的数据。"}(最新输入是纯情感时,即使历史在聊数据,也归 chat)
3) 历史(在聊员工工资),输入“他们呢?” → {"classification": "data_analysis", "response": ""}(指代与追问归 data_analysis)
4) 历史(无),输入“我们公司哪个产品卖得最好?” → {"classification": "data_analysis", "response": ""}(口语化但本质是数据查询)

要求:以本轮用户输入为主,历史仅用于理解指代与追问(如“那上个月的呢”);仅输出 JSON,不要输出其他内容;classification 必须为 data_analysis 或 chat(英文小写);chat 时 response 是直接给用户的友好简短回复,并简要说明可以帮忙查询和分析已连接的数据;data_analysis 时 response 为空字符串。',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'intent-recognition');

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'knowledge-recall',
'你是数据分析工作流的业务知识召回器:先忠实还原用户意图,把"最新用户输入"结合对话历史重写为一条可独立理解的完整查询(消解指代与省略,例如"那个呢""上个月的呢"要还原出完整对象与时间范围);不要改变原意,不要添加用户没有提出的分析维度。

【对话历史】
{history}

【最新用户输入】
{query}

要求:仅输出 JSON,不要输出其他内容;standalone_query 为重写后的完整查询(中文白话即可,不是 SQL)。
示例:
1) 历史(无),输入"我们公司哪个产品卖得最好?" → {"standalone_query": "我们公司哪个产品卖得最好"}
2) 历史(在聊员工工资),输入"那个呢?" → {"standalone_query": "员工的工资情况"}',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'knowledge-recall');
