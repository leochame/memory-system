# Memory Box - Memory-System 记忆管理系统

基于 Java + Spring Boot + LangChain4j 构建的多层记忆管理系统。

## 项目定位

- 纯 CLI 应用（无 Web UI）
- 可选启用 IM 接入（飞书长连接 / 飞书Webhook / Telegram webhook）
- 目标：在对话过程中持续积累用户洞察，并通过语义检索提升回复质量
- 架构基线：**四层记忆架构**（本仓库唯一架构基线）
- 毕设方向：在不改变 `Memory-System` 核心定位的前提下，扩展为“可记忆、可检索、可治理、可评测、可主动服务”的智能记忆体系统

## 毕设升级方向（2026-03-24）

当前项目已经具备四层记忆、RAG、Skill、CLI、IM 和 Tool 调用能力。作为毕业设计，建议继续沿着“记忆系统”主线扩展，而不是转成泛 Agent 平台或普通聊天系统。

推荐新增能力：

1. 记忆治理中心（高优先级）
   - 为每条长期记忆补充 `source / confidence / last_verified_at / status`
   - 增加冲突记忆审核、记忆合并、记忆失效和人工确认闭环
   - 支持“为什么记住这条”“为什么调用这条”的可解释展示

2. 会话摘要与情节记忆（高优先级）
   - 在 Layer 1 与 Layer 3 之间增加“会话摘要”能力，但不改变四层基线
   - 将长对话沉淀为 session summary、topic summary、milestone summary
   - 用于降低上下文长度、提升跨会话连续性

3. 多用户 / 多会话身份映射（高优先级）
   - 当前已支持 CLI / 飞书 / Telegram，多来源是天然优势
   - 建议补充统一用户身份模型，支持 `platform_user -> canonical_user`
   - 让同一用户跨端共享记忆，形成更完整的 Memory-System 闭环

4. 主动式记忆服务（高优先级）
   - 基于已存储的任务、偏好、历史主题生成提醒、回顾、跟进建议
   - 支持每日摘要、长期未完成事项提醒、周期性复盘
   - 从“能记住”升级为“会主动使用记忆”

5. 记忆检索增强（中高优先级）
   - 在现有 RAG 上增加混合检索：关键词 + 语义检索 + 类型过滤
   - 检索结果附带来源、置信度、最近命中时间
   - 支持区分用户画像、案例、技能、任务四类记忆对象

6. 记忆评测与可观测（高优先级，毕设展示价值很高）
   - 建立评测集，覆盖显式偏好提取、隐式洞察提取、冲突检测、RAG 召回、任务创建
   - 输出关键指标：准确率、召回率、冲突率、命中率、延迟、上下文压缩效果
   - 为论文提供可量化实验数据和消融实验基础

7. 技能蒸馏与案例进化（中优先级）
   - 将高质量历史案例总结为可复用 skill
   - 打通 Example -> Skill 的知识升维链路
   - 体现系统具有“从经验到方法”的持续学习能力

8. 隐私与安全控制（中优先级）
   - 增加敏感记忆标签、过期删除、导出/擦除能力
   - 提供临时记忆、只读模式、敏感字段脱敏日志
   - 使系统更适合真实用户场景和论文答辩中的安全性讨论

## 毕设推荐题目表述

- 面向多轮对话的四层智能记忆系统设计与实现
- 基于四层记忆架构的个性化对话 Memory-System 设计与实现
- 支持记忆治理与主动服务的 Memory-System 智能体研究与实现

核心表述建议始终围绕：

- 记忆的提取
- 记忆的组织
- 记忆的检索
- 记忆的治理
- 记忆的应用
- 记忆的评测

避免把项目叙事转成“大而全 Agent 平台”，这样更聚焦，也更适合作为毕设。

## 毕设阶段 Roadmap

### P1 - 记忆治理与摘要闭环

- 会话摘要文件落盘
- 记忆冲突审核 inbox
- 记忆状态机（active / pending / archived / forgotten）
- 记忆引用解释

### P2 - 多端统一用户与主动服务

- 统一用户 ID 映射
- 跨平台共享记忆
- 定时回顾 / 日报 / 周报
- 基于记忆的主动提醒建议

### P3 - 评测、实验与论文支撑

- 自动化评测脚本
- 关键指标统计
- 消融实验：无记忆 / 仅短期 / 四层完整 / 四层 + 治理 / 四层 + 主动服务
- 论文图表与案例沉淀

## 基于当前缺陷的创新建议

结合当前实现，项目现在最明显的短板不是“没有功能”，而是“知识升维、解释能力、展示表达”还不够强。

当前缺陷可概括为：

1. 记忆已能存储，但缺少来源追踪、验证和失效机制。
2. RAG 已能召回，但召回结果还不够结构化，也不能很好解释“为什么命中”。
3. Skill 已能手动加载，但 Example 还没有自动蒸馏为更高层的方法论。
4. 画像已改为单文档，但缺少会话级摘要、事件级摘要和时间线组织。
5. 已支持多端接入，但长期记忆仍缺少统一用户身份层。
6. 测试已在补，但还没有形成面向论文的指标体系和实验闭环。

在这个基础上，更有创新性的建议如下：

### 1. 案例蒸馏链路：Example -> Pattern -> Skill

不要只把历史案例当作被检索的数据，而要让系统学会“从多个案例抽象出方法”。

建议新增三步链路：

1. `Example`：保存原始问题-解决方案案例
2. `Pattern`：从多个相似案例中抽取共性步骤、适用前提、失败边界
3. `Skill`：把稳定 Pattern 蒸馏为正式技能文件，供后续按需加载

这样你的创新点就不只是“记住案例”，而是“让记忆系统具备经验抽象能力”。

### 2. 记忆时间线与里程碑图谱

当前记忆更像静态文档，建议补成“时间演化系统”：

1. 记录某条偏好首次出现的时间
2. 记录何时被再次验证
3. 标记哪些事件改变了用户画像
4. 按时间线展示用户目标、任务、主题和偏好的演进

这个方向很适合答辩展示，因为它能明显体现“系统真的在长期记忆”。

### 3. 记忆置信度衰减 + 反证机制

建议引入：

1. 置信度随时间衰减
2. 当新对话与旧记忆矛盾时触发反证检查
3. 低置信度记忆进入待验证状态
4. 连续多次被证实后再升级为稳定记忆

这会让系统从“存档器”更接近“可治理的认知系统”。

### 4. 回复级记忆引用卡片

每次回复时都可以附带一段内部可调试信息：

- 命中了哪些记忆
- 命中了哪些案例
- 是否加载了 skill
- 最终用了哪些依据生成回答

这样不仅利于调试，也非常适合现场演示。

### 5. 主动式案例沉淀

对于成功解决的问题，系统可以在后台做两件事：

1. 自动保存为 Example
2. 周期性检查哪些 Example 足够相似，可合成为 Pattern 或 Skill

这会让系统具备“越用越会做事”的成长感。

## 展示效果建议

如果你要把它作为毕设，展示重点不应该只放在“聊天成功”，而要让老师看到“记忆如何形成、如何调用、如何演化、如何验证”。

推荐展示方式：

### 1. 三段式答辩演示

第一段：无记忆模式

- 展示系统只能基于当前轮回答
- 同一问题跨轮无法保持连续性

第二段：四层记忆模式

- 展示系统记住偏好、背景和历史任务
- 展示 RAG 检索和 skill 调用

第三段：增强版 Memory-System

- 展示记忆治理、会话摘要、案例蒸馏、主动提醒
- 展示“为什么调用这条记忆”的解释卡片

这样对比会非常强。

### 2. 命令行展示增强

即使不做 Web UI，也建议把 CLI 做成更适合展示的形式：

1. 增加 `/memory-timeline`
   - 按时间展示关键偏好、任务、主题变化
2. 增加 `/memory-debug`
   - 展示本轮命中的记忆、案例、skill、分数
3. 增加 `/memory-review`
   - 展示待审核、低置信度、冲突中的记忆
4. 增加 `/memory-report`
   - 输出当前用户的记忆概览、统计信息和近期变化

### 3. 图表型展示材料

论文和答辩 PPT 中建议准备 4 张固定图：

1. 四层架构图
2. 记忆生命周期图
3. Example -> Pattern -> Skill 蒸馏流程图
4. 实验对比图：无记忆、普通记忆、增强记忆

### 4. 最容易打动老师的演示点

优先展示下面这些瞬间：

1. 系统从多次历史案例中总结出一个新 skill
2. 系统发现旧记忆与新输入冲突，要求确认而不是直接覆盖
3. 系统基于历史任务主动提醒并给出个性化建议
4. 系统展示“这次回答引用了哪些记忆依据”

相比普通聊天机器人，这些点更能突出你的项目是 `Memory-System`。

## 四层记忆架构

| 层级 | 名称 | 作用 | 当前状态 |
|------|------|------|----------|
| Layer 1 | Short-term Memory | 会话短期上下文（轮次窗口 + 用户输入日志） | 已实现 |
| Layer 2 | Metadata | 用户环境、助手偏好、全局控制开关 | 已实现 |
| Layer 3 | User Insight | 用户偏好/背景洞察，显式+隐式提取 | 已实现（单文档画像） |
| Layer 4 | Learned Knowledge | 持续学习知识（Skill + Example） | 4a 工具化已接入，4b 基础实现 |

Layer 4 拆分：
- 4a Skill（`.memory/skills/*.md`）：方法论型知识，通过 `load_skill(name)` 按需读取
- 4b Example（`vector_store.json`）：向量检索案例，当前已实现基础能力

当前基线补充：

- 新会话启动会自动加载仓库根目录 `Agent.md`
- Layer 3 已迁移为 `.memory/user-insights.md` 单文档模型，启动时支持从旧 `user_insights.json` 自动迁移
- Prompt 组装顺序已切换为“导航 + 元数据 + 历史日志 + 单文档画像 + 按需 Skill + RAG”
- `/what-you-know` 读取用户画像正文，而不是仅列出槽位

## 快速开始

### 1) 配置环境变量

```bash
export OPENAI_API_KEY="your-api-key"
export OPENAI_BASE_URL="https://api.openai.com/v1"
export OPENAI_MODEL="gpt-4"
```

### 2) 编译运行

```bash
mvn clean package
java -jar target/memory-box-1.0.0.jar

# 临时模式（不读取/不写入记忆）
java -jar target/memory-box-1.0.0.jar --temporary

# 仅运行 IM 服务（关闭交互 CLI）
java -jar target/memory-box-1.0.0.jar --cli.enabled=false
```

## IM 接入（飞书 / Telegram）

详细联调步骤见：`IM接入使用文档.md`

### 飞书官方最小文档集合（必读）

- 飞书开放平台首页：https://open.feishu.cn/
- 飞书开放平台文档总入口：https://open.feishu.cn/document/
- 服务端 SDK 总入口：https://open.feishu.cn/document/server-docs/server-side-sdk
- 机器人开发指南：https://open.feishu.cn/document/ukTMukTMukTM/uYjNwUjL2YDM14iN2ATN
- 事件订阅文档：https://open.feishu.cn/document/ukTMukTMukTM/uUTNz4SN1MjL1UzM
- 权限列表：https://open.feishu.cn/document/ukTMukTMukTM/uQjNz4CN1MjL2czN
- 发送消息 API（im.message.create）：https://open.feishu.cn/document/server-docs/im-v1/message/create
- 自建应用 tenant_access_token：https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal
- WebSocket 长连接模式（官方）：https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/event-subscription-guide/long-connection-mode

文档使用约定：优先以上官方文档；第三方文章只用于辅助排错和经验参考。

### 收消息与自动回复（推荐飞书长连接）

- 飞书长连接：启动后由官方 SDK 主动连接飞书，无需公网回调 URL
- 飞书 webhook（可选）：`POST /webhooks/feishu/event`
- Telegram webhook：`POST /webhooks/telegram`
- 主动发送接口: `POST /im/send`

接收消息后会直接调用现有对话主流程 `ConversationCli.processUserMessage()`，再回发文本消息。
当用户提出提醒/日程需求时，模型会按需调用 `create_task(...)` 工具创建任务；
任务存储在 `.memory/scheduled_tasks.json`，到点后调度器会执行任务命令（若配置了 `execute_command`），并向来源 IM 会话推送提醒。

### Python 脚本执行（LLM Tool）

系统提供 `run_python_script(script, args_json)` 工具，用于在对话中按需执行脚本：
- 仅允许 `scripts/` 目录下 `.py` 文件
- `args_json` 必须为 JSON 字符串数组
- CLI 与 IM 对话都可触发（由模型决定何时调用）

### Shell 命令执行（LLM Tool）

系统提供两个 shell 工具：
- `run_shell(command, cwd)`：只读检索（rg/cat/ls 等）
- `run_shell_command(command, cwd)`：可执行命令（可写操作）

建议：查询代码优先 `run_shell`；当用户明确要求执行命令、修改文件、创建文件时再用 `run_shell_command`。

### 定时任务创建（LLM Tool）

系统提供 `create_task(...)` 工具，用于在对话中按需创建提醒任务，支持两种模式：
- 自然语言模式：`create_task(user_message)`，由 LLM 解析标题和时间
- 前端直传模式：`create_task(title, detail, due_at_iso, execute_command, execute_timeout_seconds)`，由前端明确提供执行时间与命令
- CLI 与 IM 对话都可触发（由模型决定何时调用）

### 配置项（`application.yml`）

```yaml
im:
  api:
    enabled: ${IM_API_ENABLED:true}
  telegram:
    enabled: ${IM_TELEGRAM_ENABLED:false}
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    webhook-secret: ${TELEGRAM_WEBHOOK_SECRET:}
    webhook-url: ${TELEGRAM_WEBHOOK_URL:}
  feishu:
    enabled: ${IM_FEISHU_ENABLED:false}
    long-connection-enabled: ${FEISHU_LONG_CONNECTION_ENABLED:true}
    webhook-enabled: ${FEISHU_WEBHOOK_ENABLED:false}
    app-id: ${FEISHU_APP_ID:}
    app-secret: ${FEISHU_APP_SECRET:}
    verification-token: ${FEISHU_VERIFICATION_TOKEN:}
    webhook-url: ${FEISHU_WEBHOOK_URL:}

tool:
  shell-command:
    enabled: true
    shell: /bin/zsh
    timeout-seconds: 30
    workspace-root: .
  python:
    enabled: true
    interpreter: ${PYTHON_EXECUTABLE:python3}
    scripts-dir: scripts

scheduling:
  im-reminder-enabled: true
  task-execution-shell: /bin/zsh
  task-execution-timeout-seconds: 60
  task-execution-max-output-chars: 12000
```

说明：
- Telegram 建议配置 `webhook-secret` 并在 `setWebhook` 时设置同一 `secret_token`
- 飞书长连接模式仅需要 `FEISHU_APP_ID`、`FEISHU_APP_SECRET`
- `FEISHU_VERIFICATION_TOKEN` / `FEISHU_WEBHOOK_URL` 仅在 webhook 模式需要
- 飞书发送消息使用 `tenant_access_token/internal` + `im.message.create`
- 推荐用 `.env` 控制 IM 开关：`IM_FEISHU_ENABLED` / `IM_TELEGRAM_ENABLED`
- 任务文件可直接用 shell 修改：`.memory/scheduled_tasks.json`

### 主动发送示例

```bash
curl -X POST http://localhost:8080/im/send \
  -H "Content-Type: application/json" \
  -d '{
    "platform": "telegram",
    "conversationId": "123456789",
    "text": "hello from memory-box"
  }'
```

## CLI 命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助信息 |
| `/memories` | 查看所有记忆槽位 |
| `/what-you-know` | 查看系统记住的关于你的信息 |
| `/theme [classic|ocean|mono]` | 查看或切换 CLI 主题 |
| `/footer [on|off]` | 查看或切换底部快捷状态行 |
| `/edit <槽位名>` | 编辑指定记忆 |
| `/delete <槽位名>` | 删除指定记忆 |
| `/cleanup` | 清理长期未访问记忆 |
| `/controls` | 查看全局控制（memories/history） |
| `/controls <项> <值>` | 修改全局控制（on/off） |
| `/memory-update` | 手动触发隐式洞察提取 |
| `/search <关键词>` | 语义搜索记忆（RAG） |
| `/rag-stats` | 查看向量存储统计 |
| `/rag-rebuild` | 重建向量索引 |
| `/exit` | 退出 |

## 代码结构

```text
com.memsys/
├── MemoryBoxApplication.java              # Spring Boot 启动入口
├── cli/
│   ├── CliRunner.java                     # CLI 主循环、命令路由、展示
│   └── ConversationCli.java               # 对话主编排
├── im/
│   ├── ImRuntimeService.java              # IM 统一消息编排（收消息触发任务 + 发消息）
│   ├── ImSendController.java              # 主动发送 HTTP 接口 `/im/send`
│   ├── feishu/
│   │   ├── FeishuLongConnectionClient.java # 飞书长连接入口（官方 SDK）
│   │   ├── FeishuWebhookController.java   # 飞书 webhook 入口（可选）
│   │   └── FeishuOutboundClient.java      # 飞书消息发送（im.message.create）
│   └── telegram/
│       ├── TelegramWebhookController.java # Telegram webhook 入口
│       └── TelegramOutboundClient.java    # Telegram 消息发送（sendMessage）
├── tool/
│   └── ToolRegistry.java                  # 工具注册表（load_skill/search_rag）
├── memory/
│   ├── model/
│   │   └── Memory.java                    # 记忆对象（当前仅 USER_INSIGHT）
│   ├── storage/
│   │   └── MemoryStorage.java             # 文件持久化
│   ├── MemoryManager.java                 # Top of Mind（young/mature）
│   ├── MemoryWriteService.java            # 统一写入入口
│   ├── MemoryExtractor.java               # 提取编排
│   ├── MemoryAsyncService.java            # 单线程异步任务池
│   └── NightlyMemoryExtractionJob.java    # 夜间隐式提取任务
├── llm/
│   ├── LlmClient.java                     # 通用聊天、工具调用、结构化输出客户端
│   ├── LlmExtractionService.java          # 显式记忆+用户洞察提取
│   ├── LlmDtos.java                       # 提取结果 DTO（合并单文件）
│   ├── schema/Schemas.java                # JSON Schema
│   └── ...
├── prompt/
│   ├── AgentGuideService.java             # 启动导航加载
│   └── SystemPromptBuilder.java           # System Prompt 组装
├── skill/
│   └── SkillService.java                  # Skill 管理与按需读取
└── rag/
    └── RagService.java                    # 单文件 RAG（索引/检索/持久化）
```

## 存储文件

运行数据位于 `.memory/`：

| 文件 | 作用 |
|------|------|
| `metadata.json` | Layer 2 元数据（环境/偏好/全局控制）及 CLI UI 设置（`ui_settings`） |
| `user-insights.md` | Layer 3 用户画像正文与嵌入状态 |
| `memory_queues.json` | Layer 3 Top of Mind 队列（兼容保留） |
| `recent_user_messages.jsonl` | Layer 1 用户输入日志（滚动窗口） |
| `conversation_history.jsonl` | Layer 1 完整对话历史（append-only） |
| `vector_store.json` | Layer 4b 向量索引 |
| `pending_explicit_memories.jsonl` | 显式记忆冲突待处理队列 |
| `model_set_context.json` | 预留文件（当前主流程未启用） |
| `user_insights.json.migrated.bak` | Layer 3 迁移备份（如存在） |

## 配置

`src/main/resources/application.yml` 关键项：

```yaml
llm:
  api-key: ${OPENAI_API_KEY}
  base-url: ${OPENAI_BASE_URL}
  model-name: ${OPENAI_MODEL}

memory:
  max-slots: 100
  days-unaccessed: 30
  top-of-mind-limit: 15
  recent-messages-limit: 40
  async:
    enabled: true
    queue-capacity: 512

rag:
  enabled: true
  min-similarity-score: 0.35
  max-search-results: 5

tool:
  python:
    enabled: true
    interpreter: ${PYTHON_EXECUTABLE:python3}
    scripts-dir: scripts

cli:
  theme: classic
  show-footer: true

scheduling:
  task-reminder-check-ms: 30000
  im-reminder-enabled: true
```

## 真实 API E2E 回归（手动）

默认测试不会调用真实模型。需要手动开启：

```bash
export OPENAI_API_KEY="your-api-key"
export OPENAI_BASE_URL="https://api.openai.com/v1"   # 可选
export OPENAI_MODEL="gpt-4o-mini"                    # 可选

./scripts/run-real-api-e2e.sh
```

说明：
- 该脚本会设置 `MEMSYS_RUN_REAL_API_E2E=true` 并执行 `mvn -Dtest=RealApiE2ETest test`
- 回归日志输出到 `logs/e2e/real-api-e2e.YYYY-MM-DD_HH-MM-SS.log`

## 文档

当前保留 4 份项目文档：

- `README.md`：项目总览
- `IM接入使用文档.md`：飞书/Telegram 接入与联调指南
- `开发文档.md`：四层架构与实现规范
- `开发实现process.md`：开发实现流程与里程碑

当前待完成重点：

- 非临时模式下真实模型/API 的端到端回归结果沉淀（脚本已就绪，待跑并固化基线）
- `MemoryStorage`、`LlmClient.chatWithTools()`、`ConversationCli` 工具回路的系统化测试
- `model_set_context.json` 和 `memory_queues.json` 的最终去留决策

## 许可证

MIT License
