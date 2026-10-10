# DataBuddy

> 对话式智能数据分析平台 —— 用自然语言提问，自动完成从取数到分析报告的完整链路。

## 一、项目简介

### 1.1 背景与目标

企业数据分析的日常矛盾：**业务人员最懂业务，却不会（也不该）直接写 SQL；分析师懂技术，却被大量重复取数需求淹没**。一次并不复杂的"上个月各渠道订单总额占比"，往往要经历提需求、排期、口径对齐、来回返工多个回合，才能拿到一张口径可信的结果。

DataBuddy 的目标是把"从问题到报告"的一次完整企业分析交给智能体自动完成：业务人员用自然语言提问（如"上个月各渠道的订单总额是多少，找出占比最高的渠道"），系统自动理解业务语义（术语 / 口径 / 规则）、定位相关数据表、制定分析计划、（可选）人工确认、生成并执行 SQL、必要时用 Python 做复杂计算与可视化，最终产出结构化分析报告——让日常取数分析从"天级排期"变为"分钟级自助"。

### 1.2 业务形态

- **多智能体**：每个智能体是一个独立的数据助手，独立绑定业务库连接、数据表、术语 / 问答 / 文档 / 记忆知识库与模型配置，可按业务线或主题拆分
- **对话式交互**：问数即对话，全过程（召回、计划、SQL、结果、报告）以 SSE 事件流实时呈现；关键计划可人工确认，任务随时可停止
- **双视角管理**：业务视角（智能体知识库：术语 / 问答 / 文档 / 记忆的日常运营）；技术视角（数据连接、模型配置、提示词模板）
- **对外开放**：通过 MCP 协议把取数能力开放给 Claude / Cursor 等外部 AI 客户端

## 二、功能特性

- 全链路图工作流：意图识别 → 知识召回 → 计划 → 执行 → 报告，条件路由与失败治理内建
- 业务知识增强：表结构 / 术语 / 问答 / 文档 / 记忆五类知识源，分类索引、按序召回
- 人工确认闸：计划执行前可选人工审核，否决带意见重规划，批准恢复续跑
- SQL / Python 双组执行：取数与复杂分析双链路同构编排，失败带因回打、超限逐级升级
- Python 沙箱：Docker 容器池隔离执行 LLM 生成代码（断网 / 只读 / 非 root / 资源限额）
- 动态模型配置：Chat / Embedding 模型运行时热切换，先建后换、不中断服务
- 多轮对话与记忆：会话记忆 + 智能体级记忆（口径 / 规则 / 偏好）自动沉淀
- MCP Server：list_agents / nl2sql 两个工具对外开放
- 可观测性：节点级埋点、traceId 日志串联，可对接 Langfuse

## 三、技术栈

| 分类 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 17 |
| 应用框架 | Spring Boot 3.4.8 · WebFlux（全响应式） |
| AI 框架 | Spring AI 1.1.2（OpenAI 兼容协议 · Tika 文档解析 · VectorStore 抽象） |
| 工作流引擎 | Spring AI Alibaba Graph 1.1.0.0（StateGraph · 检查点 · 中断恢复） |
| 系统库持久化 | MySQL 8.4 · MyBatis 3.0.5 |
| 业务库接入 | Druid 1.2.22 + JDBC 方言层（连接池 · 元数据 · SQL 执行） |
| 对外协议 | SSE（流式输出）· MCP（Streamable HTTP） |
| 容器化 | Docker 多阶段构建 · Docker Compose · Python 沙箱镜像（numpy / pandas / matplotlib + 中文字体） |
| 可观测 | Micrometer Tracing · OpenTelemetry OTLP · Spring Actuator |
| 前端 | 原生 JS（零构建）· nginx 静态托管与 SSE 反代 |

## 四、系统架构

### 4.1 后端分层

```text
com.helmsail.databuddy
├── agent     智能体配置领域（Agent 定义 / 绑定 / 知识端点）
├── bottom    基础域
│   ├── aimodel       模型配置（Chat / Embedding 热切换工厂）
│   ├── bizdatabase   业务库接入（连接池 / 方言 / 元数据 / SQL 执行）
│   ├── storage       本地文件存储（路径穿越防护）
│   ├── vectorize     向量化（分类分块 / 向量读写 / 元数据契约）
│   └── async         通用异步支撑
├── middle    业务域
│   ├── graph         图工作流（节点 / 分流器 / 执行编排 / SSE 事件 / 检查点）
│   ├── biztable / bizterm / bizqa / bizdocument   知识子域
│   ├── session       会话消息域
│   ├── memory        记忆（会话记忆 + 智能体级记忆）
│   ├── python        Python 沙箱（容器池 / 执行 / 产物）
│   └── prompt        节点提示词（表化存储 / 版本 / 激活）
└── crypto / key / mcp / observation / result / exception   公共支撑
```

### 4.2 图工作流主链路

```text
意图识别 ─┬─ 闲聊 → 直接回复
          └─ 数据分析 → 知识召回 → 查询增强 → Schema 召回 → 表关系 → 可行性评估
                → 规划 →【可选人工确认闸】→ 计划执行（枢纽，逐步推进）
                     ├─ SQL 组：生成 → 校验 → 执行 ─(失败带因回打)→
                     └─ Python 组：生成 → 沙箱执行 → 结果分析 ─(失败带因回打)→
                → 报告生成
```

设计要点：

- **双组同构 + 分级治理**：两组执行链路结构一致；失败带原因打回生成，组内超限统一升级回规划（重生成 → 重规划 → 终止，每层有计数上限）
- **人工确认闸（可选）**：计划执行前可挂起等待人工审核；否决带意见重规划，批准恢复续跑；检查点持久化，进程重启不悬挂
- **增强步可回退**：知识召回 / 查询增强等增强节点失败时回退原问题继续，单点失败不炸整轮

### 4.3 关键机制

- **知识索引与召回**：五类知识源按各自策略分块向量化（表结构整块、文档结构切分、术语 / 问答单条），按 agent / 模型 / 知识类型分类管理——切换嵌入模型不串用、可回滚；图内多路召回逐步收敛
- **Python 沙箱（DooD）**：应用经挂载的 docker.sock 调度宿主 daemon 创建兄弟容器；容器池借还语义 + 空闲收缩；执行环境固定为断网、只读根、非 root、资源限额，配合软重置与残留检测保证复用安全
- **动态模型配置**：Chat / Embedding 模型先构建成功再替换，坏配置不影响现役实例；热切换后立即生效

## 五、快速启动

### 5.1 前置要求

| 依赖 | 用途 |
| --- | --- |
| Docker + Docker Compose | MySQL / 应用 / 前端 / 沙箱镜像全由 compose 编排 |
| JDK 17 | 仅"本机直跑"模式需要 |
| Node.js | 仅"本机前端静态服务"方式需要（可选） |

### 5.2 第 0 步：准备 .env（必做）

项目根创建 `.env`（已 gitignore），compose 与"本机直跑"共用同一份；逐项详解见「六、配置说明」：

```properties
APP_PORT=8080
WEB_PORT=3000
MYSQL_PORT=3306
MYSQL_ROOT_PASSWORD=change-me
DATABUDDY_AES_KEY=<base64 32字节随机值>
DATABUDDY_DB_PASSWORD=change-me
DATABUDDY_DATA_ROOT=/run/desktop/mnt/host/d/databuddy/data
PIP_INDEX=https://pypi.org/simple
APT_MIRROR=deb.debian.org
```

> ⚠️ 机密项无默认值：缺失或为空时 compose（`${VAR:?}`）与应用（启动校验）都会直接报错点名。

### 5.3 方式一：Docker Compose 一键启动（推荐）

```bash
# 1) 启动 MySQL + 应用 + 前端（首启自动建表结构与提示词种子）
docker compose up -d --build

# 2) 构建 Python 沙箱镜像（用到 Python 分析前执行一次）
docker compose --profile sandbox build

# 3) 浏览器访问 http://localhost:3000（WEB_PORT）
```

验证：

```bash
curl http://localhost:8080/actuator/health   # {"status":"UP"}
docker compose ps                            # 三个服务健康
```

### 5.4 方式二：本机直跑（开发调试）

```bash
# 1) 只起 MySQL（本机 3306 被占时改 .env 的 MYSQL_PORT）
docker compose up -d mysql

# 2) 启动后端（自动经 spring.config.import 读取 .env；端口 8080）
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run

# 3) 前端：二选一
docker compose up -d web        # 复用 nginx 镜像（3000 端口，反代同源访问 API）
# 或零依赖静态服务 + 代理：
npx http-server frontend -p 5174 -c-1 --proxy http://localhost:8080

# 4) 沙箱镜像同样需要先构建（同 5.3 第 2 步）
```

> 本机直跑时，应用同样经 docker.sock 调度宿主 Docker 创建沙箱容器——两种模式共用同一份 `DATABUDDY_DATA_ROOT` 路径约定。

### 5.5 首次使用（五步）

1. **【通用设置 → 模型配置】**：添加并激活 **Chat 模型**（图依赖 LLM）；要使用知识检索再激活 **Embedding 模型**
2. **【通用设置 → 数据连接】**：添加要分析的业务库（当前支持 MySQL）
3. **【通用设置 → 智能体管理】**：创建智能体
4. **【智能体知识库】**：从智能体进入——绑定数据表（绑后刷新向量）、录入术语 / 问答、上传文档
5. **【数据问答】**：选择智能体，开始对话

## 六、配置说明

### 6.1 `.env`（部署变量：compose 与"本机直跑"共用）

```properties
# —— 端口 ——
APP_PORT=8080                 # 应用宿主端口（compose 映射用；本机直跑固定 8080）
WEB_PORT=3000                 # 前端 nginx 宿主端口
MYSQL_PORT=3306               # MySQL 宿主端口（本机 3306 被占时改 3307 等）

# —— 密码与密钥（开发口径；正式环境务必更换） ——
MYSQL_ROOT_PASSWORD=root      # MySQL root 密码（compose 初始化库时使用）
DATABUDDY_DB_PASSWORD=root    # 应用连接系统库的密码（本机直跑用；compose 容器内由环境变量覆盖）
DATABUDDY_AES_KEY=<base64 32字节随机值>
                              # 敏感字段加密密钥（AES-256-GCM）；业务库密码等以密文落库
                              # 生成：AesUtil.generateKey()；换钥后旧密文解不开，需重新保存业务库配置

# —— 数据根（沙箱 DooD 的关键约定） ——
# 文档存储与沙箱工作目录的根；应用经 docker.sock 调宿主 daemon 创建沙箱容器时，
# -v 挂载源按 daemon 视角解析——宿主 / 容器必须使用"同一个字符串"路径：
#   Docker Desktop（Windows）：/run/desktop/mnt/host/<盘符小写>/<项目路径>
#                             例：/run/desktop/mnt/host/d/databuddy/data
#   Linux 单机：              真实路径  例：/srv/databuddy/data
DATABUDDY_DATA_ROOT=/run/desktop/mnt/host/d/databuddy/data

# —— 沙箱镜像构建源（国内网络可换镜像加速） ——
PIP_INDEX=https://pypi.org/simple     # databuddy-python 镜像的 pip 源
APT_MIRROR=deb.debian.org             # 其 apt 源（安装中文字体包）
```

### 6.2 `application.yml`（应用配置，节选讲解）

```yaml
spring:
  config:
    import: optional:file:./.env[.properties]    # 本机直跑读取项目根 .env（与 compose 共用唯一变量来源）
  datasource:
    url: jdbc:mysql://localhost:3306/databuddy…  # 系统库（提示词 / 检查点等元数据；业务库由 bizdatabase 子域管理）
    password: ${DATABUDDY_DB_PASSWORD}           # 机密：无默认值，缺失即启动失败
  sql:
    init:
      mode: always                               # 启动执行 db/schema.sql（幂等 IF NOT EXISTS）：建表 + 提示词种子
  ai:
    mcp:
      server:
        protocol: STREAMABLE                     # MCP 传输：Streamable HTTP（/mcp 自动暴露）

databuddy:
  storage:
    local-root-path: ./data/files                # 文档存储根（本机直跑相对路径；compose 用 DATABUDDY_DATA_ROOT 覆盖）
  python:
    image: databuddy-python:3.12                 # 沙箱镜像（先 docker compose --profile sandbox build）
    min-idle: 1                                  # 常驻容器数（启动后异步预热）
    max-total: 3                                 # 容器池总数上限
    idle-ttl: 10m                                # 空闲收缩：超时且多余常驻的容器销毁
    borrow-timeout: 30s                          # 借容器等待上限（超时视为容量耗尽）
    exec-timeout: 60s                            # 单次执行超时（超时容器直接销毁）
    work-root: ./data/sandbox                    # 沙箱工作目录（挂载进容器 /work）
  crypto:
    aes-key: ${DATABUDDY_AES_KEY}                # 同上：无默认值，缺失即启动失败

management:
  endpoints:
    web.exposure.include: health,metrics         # 暴露端点（验证：/actuator/health）
  tracing:
    sampling.probability: 1.0                    # 本地全采样（Boot 默认 0.1）
  # otlp:                                        # 接入 Langfuse：取消注释并填 endpoint / headers
```

## 七、目录结构

```text
databuddy
├── src/main/java/com/helmsail/databuddy
│   ├── agent/        # 智能体配置领域
│   ├── bottom/       # 基础域：模型配置 / 业务库 / 存储 / 向量化 / 异步
│   ├── middle/       # 业务域：图工作流 / 知识子域 / 会话 / 记忆 / 沙箱 / 提示词
│   └── crypto/ key/ mcp/ observation/ result/ exception/    # 公共支撑
├── src/main/resources
│   ├── db/schema.sql # 系统库表结构 + 提示词种子（启动幂等执行）
│   └── application.yml
├── frontend/         # 原生 JS 前端（聊天 / 知识库 / 管理页，零构建）
├── docker/python/    # Python 沙箱镜像构建文件
├── docs/             # 设计文档
├── data/             # 运行时数据：文档存储 / 沙箱工作目录（gitignore）
├── .env              # 本机配置（gitignore；模板见「六、配置说明」）
└── docker-compose.yml
```
