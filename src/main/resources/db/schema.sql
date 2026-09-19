-- 系统库初始化脚本:启动幂等执行(建表 IF NOT EXISTS,可反复跑)
-- 组织约定:表结构(DDL)统一放前面,初始化数据(种子 INSERT)统一放最后
-- 内容:节点提示词模板(node_prompt_template)、会话记忆(session_memory)、模型配置(ai_model_config)

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
