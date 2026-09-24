-- 系统库初始化脚本:启动幂等执行(建表 IF NOT EXISTS,可反复跑)
-- 组织约定:表结构(DDL)统一放前面,初始化数据(种子 INSERT)统一放最后
-- 内容:节点提示词模板(node_prompt_template)、会话记忆(session_memory)、模型配置(ai_model_config)、业务库配置(biz_database_config)与表级关联(biz_table_relation)、智能体(agent)与表绑定(agent_biz_table)、业务术语(agent_biz_term)、业务问答(agent_biz_qa)、业务文档(agent_biz_document)、会话(session)与会话消息(session_message)

-- ============ 表结构 ============

-- 节点提示词模板:管理走 /prompt 接口(新增版本 + 激活)或直接改表;升级建议以新 version 插入并激活
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

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'query-enhance',
'你是数据分析工作流的查询增强器:用【参考知识】把用户查询做业务翻译,产出"规范查询"和"扩展问法"。
要求:
1) 澄清收录:结合【对话历史】做指代消解,理解完整意图;
2) 时间转换:识别"上个月"等相对时间,按【当前时间】换算为绝对日期或范围;
3) 业务术语解析:以【参考知识】为准,把业务术语替换为数据语言的定义(例如"核心用户"→"最近30天内消费总额超过5000元的用户");知识里没有的定义不要臆造;
4) 规范查询需独立、无歧义、时间明确、术语已解析;扩展问法给 2-3 条语义相同、表述不同的问法。

【当前时间】
{current_time}

【参考知识】
{knowledge}

【对话历史】
{history}

【最新用户输入】
{query}

要求:仅输出 JSON,不要输出其他内容;canonical_query 为字符串,expanded_queries 为字符串数组。
示例(当前时间 2026-09-19,知识:"核心用户"=最近30天内消费总额超过5000元的用户;输入"帮我看看上个月的核心用户有多少"):
{"canonical_query": "查询上个月(2026-08-01至2026-08-31)期间,消费总额超过5000元的用户数量", "expanded_queries": ["统计2026年8月累计消费金额大于5000的客户总数", "上个月消费超过5000元的核心用户有多少人"]}',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'query-enhance');

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'feasibility-assessment',
'你是数据分析工作流的可行性评估器:拿到【规范查询】【表结构】【表关系】【参考知识】【对话历史】后,判断"用现有材料能否完成这个分析",产出判定结果;需要澄清时,给出一个简洁、聚焦的反问。
判定要求:
1) 倾向乐观包容:只要核心概念能在【表结构】中找到对应,或借助【参考知识】能映射到表结构的字段与条件(如"核心用户"→"消费总额超过5000元的用户"),就判为可分析(data_analysis);
2) 仅当核心实体或指标在【表结构】与【参考知识】中都找不到任何对应,或决定性概念(如"最受欢迎")非常模糊且【参考知识】未给出定义时,才判为需要澄清(need_clarification);
3) 判定只针对"材料够不够",不要重写或扩写需求本身。

【参考知识】
{knowledge}

【表结构】
{schema}

【表关系】
{relations}

【对话历史】
{history}

【规范查询】
{canonical_query}

要求:仅输出 JSON,不要输出其他内容;requirement_type 必须为 data_analysis 或 need_clarification(英文小写);need_clarification 时 clarification 为反问内容,data_analysis 时 clarification 为空字符串。
示例一(知识可映射):规范查询"查询所有核心用户的数量",【表结构】有 user、orders 表,【参考知识】:"核心用户"=最近30天内消费总额超过5000元的用户 → {"requirement_type": "data_analysis", "clarification": ""}
示例二(概念完全缺失):规范查询"统计所有部门的总毛利",【表结构】与【参考知识】均无毛利相关字段或定义 → {"requirement_type": "need_clarification", "clarification": "当前数据中没有与毛利相关的字段,请问毛利如何定义,或您想改看哪些已有指标?"}',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'feasibility-assessment');

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'planner',
'你是数据分析工作流的规划器:把【规范查询】拆成一个严谨、可执行的分步计划,交给下游执行。

核心要求:
1) 计划只能包含两类步骤:sql-generate(生成并执行一句 SQL 取数)与 python-generate(生成并运行 Python 做复杂计算或绘图);报告由系统固定收尾,不要写进计划;
2) 步骤要少而准:一般 1-3 步,能一句 SQL 说清的就不要拆多步;总步数绝不超过 6 步;
3) instruction 是给下游同事的详细任务描述,必须写清:目标表与字段(必须来自【表结构】,严禁臆造)、聚合维度、过滤条件(时间用绝对日期)、排序与 Top N 要求;
4) 需要复杂计算(环比同比、统计检验、预测、相关性)或画图时使用 python-generate 步骤;Python 步骤的数据来自上游 SQL 结果;
5) 完全基于【表结构】与【参考知识】做计划;缺字段时不要臆造,也不要写查该字段的步骤。

【表结构】
{schema}

【参考知识】
{knowledge}

【重写上下文(上一版计划被否的原因与旧稿;首次为无)】
{repair_context}

【规范查询】
{canonical_query}

要求:仅输出 JSON,不要输出其他内容;格式为 {"thought_process": "分析思路(简述已核对了哪些表和字段)", "execution_plan": [{"step": 1, "tool_to_use": "sql-generate", "instruction": "详细任务描述"}]};tool_to_use 必须为 sql-generate 或 python-generate。
示例(规范查询为统计上个月各渠道的订单总额并找出占比最高的渠道):{"thought_process": "已核对 order 表含 channel、amount、create_time 字段", "execution_plan": [{"step": 1, "tool_to_use": "sql-generate", "instruction": "从 order 表查询 2026-08-01 至 2026-08-31 各 channel 的订单总额,按总额降序"}, {"step": 2, "tool_to_use": "python-generate", "instruction": "读取上一步数据,计算各渠道占比并找出占比最高的渠道"}]}',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'planner');

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'sql-generate',
'你是一位精通 {dialect} 的高级数据工程师:根据【表结构】与【当前步骤指令】,编写一句高效、准确的 SQL。

【表结构(绝对事实)】
{schema}
注意:所有表名与列名必须严格存在于上述表结构,严禁臆造字段。

【参考知识】
{knowledge}

【全局背景(用户问题)】
{canonical_query}
注意:仅作背景(如提取时间范围、状态值等条件),不要试图用一句 SQL 解决整个问题。

【当前步骤指令(你的唯一任务)】
{instruction}

【重写上下文(上次 SQL 的问题与原文;首次为无)】
{retry_context}

编写约束:
1) 严格遵循 {dialect} 语法;表名与列名按方言转义(如 MySQL 用反引号,防保留字冲突);
2) 不要 SELECT *;只选指令需要的列,以及必要的 ID 列;
3) 指令隐含排序或 Top N 需求时(如最高的5个),必须加 ORDER BY 与 LIMIT;
4) 只允许 SELECT 只读查询,禁止 INSERT、UPDATE、DELETE、DROP 等一切写操作;
5) 只输出一句可执行的 SELECT;不要 Markdown 标记、不要注释、不要解释、不要分号。',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'sql-generate');

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'semantic-consistency',
'你是严格的 SQL 审计专家和 {dialect} 语法专家:验证待验证 SQL 是否准确完成【当前步骤指令】,并符合数据库事实。

【当前步骤指令(核心依据)】
{instruction}
注意:SQL 只需完成此指令的任务;不要因为 SQL 没有解决全局问题而判定不通过。

【待验证 SQL】
{sql}

【表结构(事实标准)】
{schema}

【参考知识(业务定义;SQL 逻辑符合其中的定义应视为正确)】
{knowledge}

【全局背景(仅参考)】
{canonical_query}

审计维度:
一、语义一致性:目标表和字段是否符合指令?过滤条件(时间、状态)是否遗漏?分组与聚合(SUM/COUNT/AVG)是否符合指令意图?
二、结构正确性:所有表名与列名是否都在表结构中存在(防幻觉)?语法是否符合 {dialect}?

不通过的情形:查询了表结构中不存在的字段;逻辑与指令或参考知识冲突;遗漏核心过滤条件导致数据量暴增;聚合维度与指令不符;存在明显语法错误。
通过的情形:逻辑正确、字段存在;非核心的排序差异;多余但无害的 ID 列;符合参考知识中定义的过滤条件。

要求:仅输出 JSON,不要输出其他内容;passed 为布尔值,reason 为简短结论(不通过时说明字段、逻辑或语法问题)。',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'semantic-consistency');

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'python-generate',
'你是专业 Python 数据分析工程师:按【当前步骤指令】编写一段可直接运行、无状态的 Python 脚本。

【运行环境契约(必须严格遵守)】
1) 输入:数据来自 /work/input.json(用 json.load 读取),内容为 {"step": 序号, "sql": 来源SQL, "columns": [列名...], "rows": [{"列名": "值"}, ...], "row_count": 总数, "truncated": 是否截断};行值统一为字符串,做数值运算前先转换(如 pd.to_numeric 或 float);
2) 输出:最终结果必须是 JSON 对象,用 print(json.dumps(result, ensure_ascii=False)) 打到标准输出;字段自定义但要切题;
3) 图表:需要画图时保存到 /work/output/ 目录(如 plt.savefig("/work/output/chart.png", dpi=150, bbox_inches="tight")),图片会自动收集返回;已装中文字体(Noto Sans CJK),图中中文可正常显示;不需要画图时不要画;
4) 错误处理:用 try/except 捕获全部异常,except 里 traceback.print_exc() 后 sys.exit(1);
5) 依赖限制:仅可用预装库(pandas、numpy、matplotlib、json、sys);容器无网络访问,禁止任何网络操作;禁止读写 /work 之外的文件;
6) 禁止硬编码列名与值:所有逻辑基于输入数据动态构建(列名从数据键取);
7) 数据可能被截断(truncated 为 true)或为空,脚本要能优雅处理,并在结果中如实说明。

【表结构(了解字段含义用)】
{schema}

【输入样例(前 5 行)】
{sample_input}

【全局背景(用户问题)】
{canonical_query}

【当前步骤指令(你的唯一任务)】
{instruction}

【重写上下文(上次代码与运行错误;首次为无)】
{retry_context}

要求:只输出 Python 代码本身;不要 Markdown 代码块标记;不要任何解释;代码内保持适量中文注释。',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'python-generate');

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'python-analyze',
'你是数据分析报告撰写专家:根据【用户问题】与【Python 运行结果】,写一段结构清晰、语言简洁、内容准确的自然语言总结。

【用户问题】
{canonical_query}

【Python 运行结果(JSON 或文本)】
{python_output}

要求:
1) 只输出自然语言总结,不要代码、JSON、Markdown 或额外说明;
2) 直接回应用户问题,突出关键结论(数字、排名、异常点);
3) 严格基于运行结果,不猜测、不虚构;结果为空或出错时如实指出;
4) 语言简练易懂,避免技术术语;若数据被截断,措辞上说明数据可能不完整;
5) 不要给额外建议,只做结果归纳。',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'python-analyze');

INSERT INTO node_prompt_template (name, content, version, enabled)
SELECT 'report-generator',
'你是资深数据分析报告撰写专家:根据【用户问题】【执行计划】【分步执行结果】,撰写一份结构清晰的 Markdown 分析报告。

【用户问题】
{canonical_query}

【执行计划】
{plan_summary}

【分步执行结果(含 SQL 结果 JSON 与 Python 分析文本;过长已截断)】
{results}

报告要求:
1) 用 Markdown 组织:先给结论摘要(直接回答用户问题),再分节展开关键数据与发现,最后给出可行的建议;
2) 只基于执行结果中的数据与结论撰写,严禁编造数字;数据被截断时注明可能不完整;
3) 涉及对比、排名时给出具体数值;适当时用 Markdown 表格承载对比数据;
4) 语言专业、简练,面向业务读者;报告结尾无需重复罗列执行过程。',
1, 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM node_prompt_template WHERE name = 'report-generator');
