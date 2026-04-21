# Memory Box / Memory-System

基于 Java 17、Spring Boot 与 LangChain4j 构建的四层智能记忆系统。项目聚焦“记忆如何被提取、组织、检索、治理、应用与评测”，而不是通用 Agent 平台。

## 文档入口

- `开发文档.md`：架构基线、模块边界、主流程、存储规范
- `开发实现process.md`：当前开发进度、阶段完成状态、待收尾事项
- `docs/开发计划.md`：下一阶段优先级、验收标准与收口顺序
- `docs/记忆系统内容化方案.md`：答辩/展示/对外内容资产的整理方案
- `IM接入使用文档.md`：飞书与 Telegram 接入细节

## 项目定位

- 形态：CLI 主应用，可选启用飞书 / Telegram 接入
- 架构：四层记忆架构，是本仓库唯一记忆基线
- 载体：记忆统一落到本地文件系统（`json` / `jsonl` / `md`）
- 目标：在多轮对话中形成可持续演进、可解释、可治理、可评测的记忆闭环

## 核心能力

1. Layer 1 短期记忆：保存最近对话窗口与用户输入日志，保持会话连续性。
2. Layer 2 元数据：保存用户环境、助手偏好、全局控制项与 CLI UI 设置。
3. Layer 3 用户洞察：显式与隐式提取用户长期画像，统一写入 `user-insights.md`。
4. Layer 4 持续学习知识：通过 Skill 与 Example/RAG 复用方法和历史经验。
5. 记忆反思与证据链：支持 `/memory-debug`、`/memory-review`、`/memory-report`、`/memory-governance`。
6. 摘要与时间线：沉淀 session/topic/milestone 摘要，支持 `/memory-timeline`、`/memory-scenes`。
7. 主动服务：支持任务创建、定时提醒、主动提醒记录与每周复盘。
8. 多端统一用户：CLI、飞书、Telegram 可共享记忆身份。
9. 自动评测：支持 `/benchmark`、题集查看、单题历史与批次汇总。

## 四层记忆架构

| 层级 | 名称 | 作用 | 当前状态 |
| --- | --- | --- | --- |
| Layer 1 | Short-term Memory | 最近对话与上下文连续性 | 已实现 |
| Layer 2 | Metadata | 用户环境、偏好、开关、UI 设置 | 已实现 |
| Layer 3 | User Insight | 长期偏好、背景、用户画像 | 已实现 |
| Layer 4a | Skill | 方法论型知识，按需加载 | 已实现 |
| Layer 4b | Example / RAG | 历史案例与语义检索复用 | 已实现基础能力 |

补充说明：

- 系统默认会在新会话启动时读取 `Agent.md` 作为导航文件；它不属于记忆层，而是启动上下文入口。
- 记忆写入统一经过 `MemoryWriteService`。
- 分层调用约束保持为 `cli -> memory/rag -> llm -> prompt`。

## 快速开始

### 1. 配置环境变量

```bash
export OPENAI_API_KEY="your-api-key"
export OPENAI_BASE_URL="https://api.openai.com/v1"
export OPENAI_MODEL="gpt-4"
```

### 2. 编译与运行

```bash
# 仅编译
./scripts/run-compile.sh

# 运行测试
./scripts/run-tests.sh

# 打包后运行
java -jar target/memory-box-1.0.0.jar

# 临时模式：不读取/不写入记忆
java -jar target/memory-box-1.0.0.jar --temporary

# 仅运行 IM 服务
java -jar target/memory-box-1.0.0.jar --cli.enabled=false
```

## 常用 CLI 命令

| 命令 | 说明 |
| --- | --- |
| `/help` | 查看命令总览 |
| `/memories` | 查看当前记忆内容 |
| `/tasks` | 查看定时任务 |
| `/what-you-know` | 查看用户画像正文 |
| `/controls` | 查看或切换记忆/历史开关 |
| `/memory-update` | 手动触发隐式洞察提取 |
| `/memory-debug [N]` | 查看最近一轮或最近 N 轮记忆反思与证据链 |
| `/memory-review` | 一页式记忆复盘 |
| `/memory-report` | 记忆系统综合状态报告 |
| `/memory-timeline` | 查看摘要与关键事件时间线 |
| `/memory-scenes` | 按场景聚合展示摘要 |
| `/memory-insights [N]` | 输出证据质量洞察 |
| `/memory-governance` | 查看冲突、待审核和归档状态 |
| `/memory-approve <序号>` | 批准待处理记忆 |
| `/memory-reject <序号>` | 拒绝待处理记忆 |
| `/benchmark` | 运行默认 benchmark |
| `/benchmark history [N]` | 查看最近 N 条评测记录 |
| `/benchmark reports [N]` | 查看最近 N 次批次汇总 |
| `/benchmark dataset` | 查看当前 benchmark 题集 |
| `/search <关键词>` | 语义检索记忆 |
| `/rag-stats` | 查看向量索引统计 |
| `/rag-rebuild` | 重建向量索引 |
| `/skills` | 查看技能列表 |
| `/examples` | 查看 example 数据情况 |
| `/identity` | 查看统一身份映射 |
| `/scope ...` | 切换个人 / 团队记忆作用域 |
| `/weekly-report` | 生成当前作用域周报 |
| `/exit` | 退出 |

## IM 与工具能力

### IM 接入

- 飞书长连接：推荐模式，无需公网回调 URL
- 飞书 webhook：`POST /webhooks/feishu/event`
- Telegram webhook：`POST /webhooks/telegram`
- 主动发送：`POST /im/send`
- 对话接口：`POST /im/chat`
- SSE 流式对话：`POST /im/chat/stream`

详细接入步骤见 `IM接入使用文档.md`。

### 可用工具

- `load_skill(name)`：按需读取 Skill 正文
- `search_rag(query)`：按需检索相似案例或记忆
- `run_shell(command, cwd)`：只读 shell 检索
- `run_shell_command(command, cwd)`：执行命令或修改文件
- `run_python_script(script, args_json)`：执行 `scripts/` 下 Python 脚本
- `create_task(...)`：创建提醒任务

## 代码结构

```text
src/main/java/com/memsys/
├── MemoryBoxApplication.java
├── cli/         # CLI 命令与对话主编排
├── im/          # 飞书 / Telegram 接入
├── llm/         # 模型通信与结构化提取
├── memory/      # 记忆读写、提取、摘要、治理
├── prompt/      # Agent 导航与 system prompt 组装
├── rag/         # 向量索引、检索与统计
├── skill/       # Skill 管理
└── tool/        # 工具注册与执行
```

## `.memory/` 主要文件

| 文件 | 作用 |
| --- | --- |
| `metadata.json` | Layer 2 元数据与 UI 设置 |
| `user-insights.md` | Layer 3 用户画像正文 |
| `conversation_history.jsonl` | 完整对话历史 |
| `recent_user_messages.jsonl` | 用户输入窗口 |
| `memory_queues.json` | Top of Mind 队列兼容结构 |
| `pending_explicit_memories.jsonl` | 待审核显式记忆 |
| `vector_store.json` | Layer 4b 向量索引 |
| `scheduled_tasks.json` | 定时任务 |
| `pending_task_notifications.jsonl` | 待发送提醒 |
| `session_summaries.jsonl` | 会话摘要 |
| `topic_summaries.jsonl` | 话题摘要 |
| `milestone_summaries.jsonl` | 里程碑摘要 |
| `eval_results.jsonl` | 单题评测历史 |
| `benchmark_questions.txt` | benchmark 外部题集 |
| `benchmark_reports.jsonl` | benchmark 批次汇总 |
| `model_set_context.json` | 预留结构，当前未进入主链路 |

## 当前状态

项目已完成主体功能建设，当前阶段重点是：

1. 固化真实模型/API 的 E2E 基线。
2. 补齐 benchmark 数据集、指标口径与实验闭环。
3. 收敛兼容结构与预留文件的去留说明。
4. 补少量高价值演示增强能力。

开发进度请看 `开发实现process.md`，后续排期请看 `docs/开发计划.md`。

## 开发约束

1. 四层架构是唯一基线，不新增第五层。
2. 记忆写入统一经过 `MemoryWriteService`。
3. `LlmClient` 只负责模型通信，提取逻辑放在 `LlmExtractionService`。
4. 优先使用本地文件作为记忆载体，保持可恢复、可查看、可迁移。
5. 文档更新时保持 `README.md`、`开发文档.md`、`开发实现process.md`、`docs/开发计划.md` 口径一致。
