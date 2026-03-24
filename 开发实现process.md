# 记忆系统开发实现 Process

## 1. 目标与范围

本文件定义“怎么落地四层架构”，用于指导后续开发、验收和迭代。

- 架构目标：稳定落地四层记忆体系（L1/L2/L3/L4）
- 交付目标：每个阶段都有可运行结果、可验证标准、可回滚方案
- 文档目标：保证实现与 `README.md`、`开发文档.md` 一致

当前日期基线：2026-03-24

---

## 2. 开发流程（统一）

每个需求均按以下流程执行：

1. 需求确认
2. 架构映射（落到四层中的哪一层）
3. 设计评审（数据结构、接口、文件格式、兼容迁移）
4. 编码实现（最小闭环）
5. 本地验证（编译、核心链路、回归）
6. 文档更新（README + 开发文档 + 本文件）
7. 合并与发布

---

## 3. 分阶段实现计划

### Phase 0 - 基础骨架（已完成）

交付：

1. Spring Boot CLI 可启动
2. 基础配置和日志可用
3. `.memory/` 目录自动初始化

验收标准：

1. 应用能正常启动
2. 初始化文件能自动创建

---

### Phase 1 - Layer 1 短期记忆（已完成）

交付：

1. `conversation_history.jsonl` 追加写
2. `recent_user_messages.jsonl` 滚动窗口
3. 最近轮次上下文注入

验收标准：

1. 连续对话时上下文可复用
2. 超出窗口后旧消息自动滚出

---

### Phase 2 - Layer 2 元数据（已完成）

交付：

1. `metadata.json` 读写
2. 全局控制（`use_saved_memories/use_chat_history`）
3. CLI `/controls` 命令

验收标准：

1. 控制开关生效
2. 重启后配置可恢复

---

### Phase 2.5 - 会话启动导航 `Agent.md`（已完成）

交付：

1. 仓库根目录 `Agent.md`
2. 新会话启动时自动加载 `Agent.md`
3. `Agent.md` 只承担导航职责，不承载大段知识正文
4. `Agent.md` 中标注自动加载和按需加载边界

验收标准：

1. 新会话开始前可以稳定读取 `Agent.md`
2. `Agent.md` 内容保持简短，可作为文件导航图使用
3. 启动阶段不会因为 `Agent.md` 引入大量无关上下文

---

### Phase 3 - Layer 3 用户洞察（已完成基础）

交付：

1. 显式记忆提取
2. 隐式用户洞察提取（夜间任务 + 手动触发）
3. Top of Mind 双队列管理
4. 冲突记忆入 `pending_explicit_memories.jsonl`

验收标准：

1. 显式偏好可提取并写入
2. 隐式洞察可批量写入
3. 队列访问权重随交互变化

---

### Phase 3.5 - Layer 3 文件系统化（已完成）

交付：

1. `.memory/user-insights.md` 作为唯一正文文件
2. `User Insight` 改为单文档维护，不再按槽位拆分多个 Markdown 文件
3. 文档正文采用自然段表达，不使用项目符号列点
4. 从 `user_insights.json` 自动迁移到单文档布局

验收标准：

1. 用户画像可以作为单个 Markdown 文档查看和编辑
2. 文档内容是连续叙述，不是按点罗列
3. 迁移后旧数据不丢失

---

### Phase 4 - Layer 4b Example（已完成基础）

交付：

1. 本地嵌入模型接入
2. 向量存储读写与检索
3. 对话期 RAG 上下文注入
4. `/search`、`/rag-stats`、`/rag-rebuild` 命令

验收标准：

1. 输入相关 query 能返回语义匹配结果
2. 向量索引可重建并持久化

---

### Phase 5 - Layer 4a Skill（已完成基础）

交付：

1. `.memory/skills/*.md` 存储规范
2. `load_skill(name)` 固定工具
3. prompt 中的可用 skill 列表与按需加载策略
4. skill 生命周期基础命令（查看、保存、删除、生成）

验收标准：

1. 能新增并读取 skill 文件
2. 模型可以按名称精确加载单个 skill
3. 命中 skill 时模型回复质量可观测提升

---

### Phase 5.5 - 渐进式文件加载与工具策略（已完成基础）

交付目标：

1. 固定工具 `load_skill(name)` 作为高频资源入口
2. 其他文件默认走只读 shell 方式按需读取
3. shell 命令限制在白名单：`rg`、`grep`、`head`、`tail`、`sed -n`、`ls`、`find`、`cat`
4. 落实“工具优先，shell 兜底”的加载策略

验收标准：

1. `Skill` 不再依赖每轮全量扫描和全文注入
2. 其他文件可以通过只读 shell 完成定位和局部读取
3. 默认不开放任意写 shell 命令
4. 启动阶段保持最小上下文，只自动加载 `Agent.md`

---

### Phase 6 - 稳定性与测试（进行中）

交付目标：

1. 单元测试补齐（storage/llm/cli/rag）
2. 核心链路集成测试
3. 编译与运行检查纳入固定流程
4. 非临时模式下真实模型/API 的端到端回归

验收标准：

1. 关键模块有可重复自动化验证
2. 回归修改不破坏对话主链路
3. `load_skill(name)`、画像迁移、RAG 注入具备固定回归用例

---

### Phase 7 - Memory Reflection 与证据视图（已完成）

交付目标：

1. 引入 `Memory Reflection` 机制，先判断回答是否需要过去证据
2. 为每轮回答记录 `Memory Evidence Trace`
3. 新增 `/memory-debug` 命令，展示本轮记忆反思与证据使用情况
4. 主链路支持“需要记忆”和“不需要记忆”两类问题的稳定处理

验收标准：

1. 系统能输出”为什么这轮需要或不需要记忆”
2. 调用记忆时能说明使用目的和证据类型
3. 反思阶段失败不会阻断主对话链路

#### 迭代记录 - 2026-03-24 20:00

- 增强目标：创建 MemoryReflectionService 骨架，实现回答前的记忆需求判断
- 涉及文件：新增 `src/main/java/com/memsys/memory/MemoryReflectionService.java`、新增 `src/main/java/com/memsys/memory/model/ReflectionResult.java`、修改 `ConversationCli.java`（接入反思链路）
- 实现方案：新建 MemoryReflectionService，通过 LlmExtractionService 调用 LLM 判断当前用户消息是否需要长期记忆，返回 ReflectionResult（needsMemory, reason, evidencePurpose）。在 ConversationCli.generateResponse 中接入，反思失败时 fallback 为默认行为（照常加载记忆），不阻断主链路
- 状态：已完成
- 实际修改文件：
  - 新增 `src/main/java/com/memsys/memory/model/ReflectionResult.java` — 反思结果数据模型
  - 新增 `src/main/java/com/memsys/memory/MemoryReflectionService.java` — 反思服务核心逻辑
  - 修改 `src/main/java/com/memsys/llm/LlmDtos.java` — 新增 MemoryReflectionResult DTO
  - 修改 `src/main/java/com/memsys/llm/schema/Schemas.java` — 新增 memoryReflectionResult() JSON Schema
  - 修改 `src/main/java/com/memsys/llm/LlmExtractionService.java` — 新增 reflectMemoryNeed() 方法
  - 修改 `src/main/java/com/memsys/cli/ConversationCli.java` — 接入反思链路，根据反思结果决定是否加载记忆
- 实际结果：系统在回答前先通过 LLM 判断"当前问题是否需要长期记忆"，不需要时跳过记忆加载；反思失败时 fallback 为默认加载记忆，不阻断主链路

#### 迭代记录 - 2026-03-24 21:30

- 增强目标：实现 `/memory-debug` 命令与 Memory Evidence Trace 数据结构，让每轮回答的记忆反思与证据使用情况可观测
- 涉及文件：新增 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、修改 `ConversationCli.java`（记录证据跟踪）、修改 `CliRunner.java`（注册 `/memory-debug` 命令）
- 实现方案：
  1. 新增 `MemoryEvidenceTrace` record，记录一轮回答中的反思结果 + 实际加载了哪些记忆源（TopOfMind/RAG/UserInsight/Example/Skill）
  2. 在 `ConversationCli.generateResponse()` 中构建 `MemoryEvidenceTrace`，记录本轮使用的证据类型与数量
  3. 在 `CliRunner` 中注册 `/memory-debug` 命令，展示最近一轮的反思结果和证据使用详情
- 状态：已完成
- 实际修改文件：
  - 新增 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java` — 证据跟踪数据模型（record），记录反思结果 + 各证据源使用情况 + 可视化展示方法
  - 修改 `src/main/java/com/memsys/cli/ConversationCli.java` — generateResponse 中构建 EvidenceTrace，记录 TopOfMind/RAG/UserInsight/Example/Skill 使用量；新增 `getLastEvidenceTrace()` 访问器
  - 修改 `src/main/java/com/memsys/cli/CliRunner.java` — 注册 `/memory-debug` 命令，展示最近一轮反思与证据使用详情
- 实际结果：用户可通过 `/memory-debug` 命令查看最近一轮回答的完整记忆反思过程（需要/不需要记忆、判断理由、证据用途）和实际证据加载情况（TopOfMind 条数、RAG 命中数、用户画像、Example、Skill）

---

### Phase 8 - 会话摘要与场景化展示（进行中）

交付目标：

1. 会话摘要自动生成并落盘
2. 长对话主题切换时生成 topic summary
3. Prompt 优先使用摘要压缩历史上下文
4. 新增 `/memory-timeline`、`/memory-report`、`/memory-scenes`、`/tasks` 等展示命令

验收标准：

1. 长会话下 prompt 长度明显下降
2. 摘要后仍能保持对关键上下文的连续理解
3. 摘要文件可独立查看和复用
4. CLI 输出可直接用于答辩场景展示

#### 迭代记录 - 2026-03-24 22:30

- 增强目标：创建 ConversationSummaryService 骨架，实现会话摘要通过 LLM 自动生成并落盘到 `.memory/session_summaries.jsonl`
- 涉及文件：新增 `LlmDtos.ConversationSummaryResult` DTO、新增 `Schemas.conversationSummaryResult()` Schema、修改 `LlmExtractionService`（新增 summarizeConversation 方法）、新增 `ConversationSummaryService.java`、修改 `MemoryStorage`（新增 session_summaries.jsonl 读写）、修改 `ConversationCli.java`（轮次阈值触发摘要）
- 实现方案：
  1. 新增 DTO `ConversationSummaryResult(summary, key_topics, turn_count, timestamp_range)` 用于 LLM 结构化摘要输出
  2. 在 Schemas 中新增对应 JSON Schema
  3. LlmExtractionService 新增 `summarizeConversation(historyText)` 方法调用 LLM 生成摘要
  4. 新增 ConversationSummaryService，封装"何时生成摘要"和"摘要落盘"的逻辑
  5. MemoryStorage 新增 `appendSessionSummary()` 和 `readSessionSummaries()` 方法
  6. ConversationCli 在每轮对话后检查轮次，达到阈值（如 20 轮）时异步触发摘要生成
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/llm/LlmDtos.java` — 新增 ConversationSummaryResult DTO（summary, key_topics, turn_count, time_range）
  - 修改 `src/main/java/com/memsys/llm/schema/Schemas.java` — 新增 conversationSummaryResult() JSON Schema，含 JsonIntegerSchema 支持
  - 修改 `src/main/java/com/memsys/llm/LlmExtractionService.java` — 新增 summarizeConversation() 方法，通过 LLM 生成结构化会话摘要
  - 新增 `src/main/java/com/memsys/memory/ConversationSummaryService.java` — 会话摘要服务骨架，含轮次计数、阈值触发、LLM 摘要生成、jsonl 落盘
  - 修改 `src/main/java/com/memsys/memory/storage/MemoryStorage.java` — 新增 session_summaries.jsonl 初始化、appendSessionSummary()、readSessionSummaries() 方法
  - 修改 `src/main/java/com/memsys/cli/ConversationCli.java` — 注入 ConversationSummaryService，每轮对话完成后计数并在达到阈值时异步触发摘要生成
- 实际结果：系统在对话达到 20 轮阈值时自动通过 LLM 生成会话摘要（含摘要文本、关键话题、轮次数、时间范围），落盘到 `.memory/session_summaries.jsonl`；摘要生成失败不阻断主对话链路；MemoryStorage 提供摘要读写能力供后续 prompt 压缩和展示使用

#### 迭代记录 - 2026-03-24 23:30

- 增强目标：Prompt 优先使用摘要压缩历史上下文 — 当有会话摘要时，用摘要替代 olderUserMessages 原始消息注入 system prompt，大幅降低 prompt 长度
- 涉及文件：修改 `SystemPromptBuilder.java`（新增 sessionSummariesText 参数和对应 prompt 段落）、修改 `ConversationCli.java`（在 buildSystemPromptWithEvidence 中读取最近摘要并传递给 SystemPromptBuilder）
- 实现方案：
  1. SystemPromptBuilder.buildSystemPrompt 新增 sessionSummariesText 参数
  2. 当 sessionSummariesText 非空时，在 prompt 中新增"## 4a. 历史对话摘要"段落展示摘要文本
  3. 当有摘要可用时，跳过 olderUserMessages（原始冗长消息），改为仅展示摘要 — 压缩效果明显
  4. ConversationCli.buildSystemPromptWithEvidence 中读取 conversationSummaryService.getRecentSummaries(3)，格式化后传递
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/prompt/SystemPromptBuilder.java` — buildSystemPrompt 新增 sessionSummariesText 参数；当有摘要时用"历史对话摘要（压缩模式）"段落替代原始 recentMessages 注入；重要说明中新增摘要模式提示
  - 修改 `src/main/java/com/memsys/cli/ConversationCli.java` — buildSystemPromptWithEvidence 中读取 conversationSummaryService.getRecentSummaries(3)，格式化为 Markdown 文本传递给 SystemPromptBuilder；当有摘要时跳过 olderUserMessages 实现 prompt 压缩
- 实际结果：当 session_summaries.jsonl 中有摘要记录时，system prompt 第 4 节改为展示压缩后的摘要文本（含时间范围和话题关键词），同时跳过 olderUserMessages（原始第 11-40 轮用户消息），prompt 长度显著下降；无摘要时行为不变，兼容向后

#### 迭代记录 - 2026-03-25 00:00

- 增强目标：新增 `/memory-timeline` 和 `/memory-report` 展示命令，让记忆系统的全貌和演变可在 CLI 中直观展示，满足答辩场景展示需求
- 涉及文件：修改 `CliRunner.java`（注入 ConversationSummaryService + 新增两个命令路由和展示方法）
- 实现方案：
  1. CliRunner 注入 ConversationSummaryService
  2. `/memory-timeline` — 时间线视图：展示记忆系统的事件流（最近对话、摘要生成、记忆提取、Evidence Trace）
  3. `/memory-report` — 记忆报告视图：汇总展示当前记忆系统状态（各层状态、记忆数量、摘要数量、RAG 状态等）
  4. 在 handleCommand switch 和 buildCommandDescriptions 中注册
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/cli/CliRunner.java` — 注入 ConversationSummaryService；新增 `/memory-timeline` 命令（时间线视图：展示摘要事件流 + Evidence Trace + 会话状态）；新增 `/memory-report` 命令（综合报告：L1-L4 各层状态 + 摘要数量 + Prompt 压缩状态 + 记忆反思状态）；注册命令路由和描述
- 实际结果：
  - `/memory-timeline` 可展示最近 5 条会话摘要的时间线（含轮次范围、时间段、摘要内容、话题关键词）+ 最近一轮 Evidence Trace 概览 + 当前会话轮次
  - `/memory-report` 可展示记忆系统全貌：L1 短期记忆轮次、L2 全局开关状态、L3 记忆槽位数和画像状态、L4a Skill 列表、L4b RAG 索引统计、会话摘要数和 Prompt 压缩状态、记忆反思运行状态
  - 两个命令可直接用于答辩现场演示，展示系统能力全景

#### 迭代记录 - 2026-03-25 01:00

- 增强目标：长对话主题切换时自动检测并生成 topic summary，补齐 Phase 8 交付项 #2
- 涉及文件：修改 `LlmDtos.java`（新增 TopicShiftDetectionResult DTO）、修改 `Schemas.java`（新增 topicShiftDetectionResult Schema）、修改 `LlmExtractionService.java`（新增 detectTopicShift 方法）、修改 `ConversationSummaryService.java`（新增主题切换检测与触发逻辑）、修改 `ConversationCli.java`（在每轮对话后增加主题切换检测触发）
- 实现方案：
  1. 新增 TopicShiftDetectionResult DTO（topic_shifted, previous_topic, current_topic）
  2. 在 Schemas 中新增对应 JSON Schema
  3. LlmExtractionService 新增 detectTopicShift(recentContext, currentMessage) 方法
  4. ConversationSummaryService 新增 checkAndHandleTopicShift() 方法，检测到主题切换时触发前一段话题的摘要生成
  5. ConversationCli 在每轮对话后异步调用主题切换检测
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/llm/LlmDtos.java` — 新增 TopicShiftDetectionResult DTO（topic_shifted, previous_topic, current_topic）
  - 修改 `src/main/java/com/memsys/llm/schema/Schemas.java` — 新增 topicShiftDetectionResult() JSON Schema
  - 修改 `src/main/java/com/memsys/llm/LlmExtractionService.java` — 新增 detectTopicShift(recentContext, currentMessage) 方法，通过 LLM 结构化判断主题是否切换
  - 修改 `src/main/java/com/memsys/memory/ConversationSummaryService.java` — 新增 checkTopicShiftAndSummarize() 方法，检测到主题切换时自动生成前一段话题的摘要；落盘记录新增 trigger/previous_topic/current_topic 字段区分触发来源；新增最低轮次保护（TOPIC_SHIFT_MIN_TURNS=3）
  - 修改 `src/main/java/com/memsys/cli/ConversationCli.java` — 每轮对话后异步调用主题切换检测，与轮次阈值触发互补
- 实际结果：系统在每轮对话完成后异步检测主题切换，当检测到显著话题转变时自动生成前一段话题的摘要并落盘；摘要记录中标注触发来源（topic_shift/turn_threshold）和前后话题名称；前 3 轮不检测，避免新会话初期频繁触发；检测失败不阻断主对话链路

---

### Phase 9 - 记忆治理、主动服务与多用户统一身份（计划中）

交付目标：

1. 为长期记忆补齐 `status / source / confidence / verification` 元数据
2. 支持 CLI / 飞书 / Telegram 的统一身份映射
3. 跨平台共享同一用户画像与任务
4. 支持用户通过自然语言调用大模型创建定时任务
5. 基于记忆生成主动提醒、回顾和建议

验收标准：

1. 同一用户跨平台能读取同一长期画像
2. 冲突记忆不再直接覆盖，并可审核
3. 用户能通过自然语言稳定创建定时任务
4. 定时任务与回顾消息能正确路由到对应来源
5. 主动服务逻辑不会污染其他用户的记忆空间

---

### Phase 10 - 评测、实验与论文支撑（计划中）

交付目标：

1. 建立固定评测集与脚本
2. 输出核心指标：提取准确率、召回率、命中率、延迟、压缩收益
3. 提供消融实验配置
4. 沉淀论文可直接使用的案例与图表数据

验收标准：

1. 关键实验可重复执行
2. 改动前后效果可量化对比
3. README、开发文档、本文件三者同步记录实验口径

---

### Phase 10.5 - 案例蒸馏与方法学习（计划中，毕设创新加分项）

交付目标：

1. 建立 `Example -> Pattern -> Skill` 蒸馏链路
2. 从多个相似案例中提炼稳定 Pattern
3. Pattern 可进一步生成 Skill 草稿
4. 准备答辩级演示脚本与固定案例

验收标准：

1. 多个相似案例能够合成为可复用 Pattern
2. Pattern 能进一步沉淀为正式 Skill
3. Pattern 至少包含步骤、适用边界、失败点
4. 系统展示效果能明显区分于普通聊天系统

---

## 4. 当前实现状态（2026-03-24 快照）

1. 四层架构中，L1/L2/L3 已落地，L3 已迁移为 `.memory/user-insights.md` 单文档画像。
2. L4 中 4b（Example/RAG）基础可用。
3. `Agent.md` 启动自动加载已接入 `AgentGuideService`。
4. L4 中 4a（Skill）已收敛为 `load_skill(name)` 工具优先模式。
5. `/what-you-know` 已切换为读取用户画像正文。
6. `model_set_context.json` 为预留，未接入 runtime。
7. `ToolRegistry` 已独立管理工具，`load_skill(name)` 与 `search_rag(query)` 已接入对话主链路。
8. CLI 输入层已升级为 JLine（历史、补全、行编辑）并完成 Gemini 风格基础 UI 对齐（banner/卡片/状态行/spinner）。
9. CLI 已支持 `/theme` 与 `/footer` 运行时切换，并通过 `metadata.json.ui_settings` 持久化（临时模式不持久化）。
10. 真实 API E2E 已落地手动回归入口：`RealApiE2ETest` + `scripts/run-real-api-e2e.sh`（默认不自动执行）。
11. 项目毕设升级方向已明确收敛为：Memory Reflection、证据视图、会话摘要、场景化展示、多用户身份映射、自然语言任务、主动服务、案例蒸馏与评测。
12. Phase 7 Memory Reflection 骨架已落地：`MemoryReflectionService` + `ReflectionResult` + LLM 结构化判断 + `ConversationCli` 主链路接入，支持根据反思结果决定是否加载长期记忆。
13. Phase 7 Memory Evidence Trace 已落地：`MemoryEvidenceTrace` 记录每轮证据使用，`/memory-debug` 命令可展示反思结果与证据加载详情。
14. Phase 8 会话摘要基础已落地：`ConversationSummaryService` + LLM 结构化摘要 + `session_summaries.jsonl` 落盘 + 轮次阈值触发，支持对话达到 20 轮时自动生成摘要。
15. Phase 8 Prompt 摘要压缩已落地：当有会话摘要时，system prompt 用摘要替代 olderUserMessages 原始消息注入，大幅降低 prompt 长度；无摘要时行为不变。
16. Phase 8 场景化展示命令已落地：`/memory-timeline` 展示记忆事件时间线，`/memory-report` 展示记忆系统全层状态报告，可直接用于答辩场景展示。
17. Phase 8 主题切换检测已落地：每轮对话后异步检测主题是否切换，切换时自动生成前一段话题摘要；摘要落盘记录包含触发来源（topic_shift/turn_threshold）和前后话题名称，与轮次阈值触发互补。

---

## 5. 变更执行清单（每次提交前）

1. 代码层
   - 是否破坏分层依赖
   - 是否绕过 `MemoryWriteService`
   - 是否影响 `.memory` 文件兼容
2. 运行层
   - 启动是否正常
   - `Agent.md` 是否按预期自动加载
   - 关键命令是否可用（`/memory-update`、`/search`）
3. 文档层
   - `README.md` 是否同步
   - `开发文档.md` 是否同步
   - 本文件状态是否更新

---

## 6. 风险与应对

1. 风险：LLM 结构化输出不稳定
   - 应对：Schema 约束 + legacy fallback
2. 风险：异步队列积压
   - 应对：单线程限流 + 队列容量监控
3. 风险：shell 读取过度导致上下文膨胀
   - 应对：固定工具优先、shell 白名单、按需局部读取
4. 风险：单文档重写时容易覆盖已有用户画像
   - 应对：统一写入入口、改为整体合并重写、保留迁移源做回退
5. 风险：文档与实现漂移
   - 应对：合并前执行文档一致性检查

---

## 7. 文档收敛规则

项目文档只保留三份：

1. `README.md`
2. `开发文档.md`
3. `开发实现process.md`

新增文档前，优先把内容合并到上述三份中，避免文档分叉。

---

## 8. 毕设执行顺序建议（当前生效）

1. 先做 `Phase 7` Memory Reflection 与证据视图
   - 原因：它直接解决“系统为什么调用记忆”这个核心问题
2. 再做 `Phase 8` 会话摘要与场景化展示
   - 原因：它直接提升 Memory-System 的连续性和答辩表现力
3. 然后做 `Phase 9` 记忆治理、自然语言任务、主动服务与多用户身份映射
   - 原因：当前 IM 架构已经具备入口，扩展性最好
4. 最后做 `Phase 10` 评测与论文支撑
   - 原因：应在功能相对稳定后统一固化实验口径
5. 若时间允许，补 `Phase 10.5` 案例蒸馏与展示增强
   - 原因：这部分对毕设创新性和现场展示效果提升最大
