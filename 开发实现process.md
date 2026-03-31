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

### Phase 8 - 会话摘要与场景化展示（已完成）

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

#### 迭代记录 - 2026-03-25 01:30

- 增强目标：新增 `/memory-scenes` 命令，按话题/场景分组展示记忆摘要，补齐 Phase 8 交付项 #4 中的场景化展示命令，覆盖答辩演示场景 #5
- 涉及文件：修改 `CliRunner.java`（新增 `/memory-scenes` 命令路由和展示方法）
- 实现方案：
  1. 从 session_summaries.jsonl 读取所有摘要
  2. 按 key_topics 聚类展示：同一话题下的摘要归组
  3. 展示每个场景的时间范围、轮次、摘要内容
  4. 在 handleCommand switch 和 buildCommandDescriptions 中注册
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/cli/CliRunner.java` — 新增 `/memory-scenes` 命令路由和展示方法 showMemoryScenes()；按话题聚类展示摘要（从 key_topics 和 topic_shift 元数据中提取话题），每个场景展示关联摘要的轮次范围、时间段、触发来源、摘要内容；展示末尾包含统计（轮次阈值 vs 主题切换触发数量）；注册命令路由和描述
- 实际结果：
  - `/memory-scenes` 可按话题/场景分组展示所有会话摘要，同一话题下的多条摘要归入同一场景
  - 每个场景展示话题名称、关联摘要数、各摘要的轮次范围和触发来源
  - 末尾统计展示轮次阈值触发 vs 主题切换触发的数量对比
  - 可直接用于答辩场景 #5 演示

#### 迭代记录 - 2026-03-29 00:50

- 增强目标：补齐 `/memory-review` 场景化展示命令，满足开发文档 6.3 清单缺口并提升答辩可演示性
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`、更新 `README.md` 命令清单与说明、更新 `开发实现process.md` 状态快照
- 实现方案：在 CliRunner 增加 `/memory-review` 命令，按“最近反思 + 最近摘要 + 治理状态 + 近期任务”输出一页记忆复盘视图；同步命令注册与帮助文案；文档补充新命令用途与示例
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/cli/CliRunner.java` — 新增 `/memory-review` 命令路由与 `showMemoryReview()` 展示方法；展示最近反思证据、会话摘要、治理概览、近期任务；同步帮助描述与底部快捷命令提示
  - 修改 `README.md` — 在 CLI 命令表补充 `/memory-review` 及相关记忆展示命令条目
  - 修改 `开发实现process.md` — 记录本次迭代并更新当前状态快照
- 实际结果：
  - `/memory-review` 可一页式复盘记忆系统关键状态：反思决策、摘要压缩、治理健康度、任务执行态
  - 开发文档 6.3 场景化命令清单中的 `/memory-review` 缺口已补齐
  - 本地编译通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn compile -q`

#### 迭代记录 - 2026-03-29 10:40

- 增强目标：按开发文档 6.1（Step 1/6）补齐“将反思结果传入 `SystemPromptBuilder`”缺口，确保 Memory Reflection 决策可被主提示词显式消费
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、修改 `src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java`
- 实现方案：
  1. `ConversationCli.buildSystemPromptWithEvidence(...)` 新增 `ReflectionResult` 参数
  2. 主链路与评测链路调用 `buildSystemPromptWithEvidence(...)` 时统一传入当前轮 `reflection`
  3. `SystemPromptBuilder.buildSystemPrompt(...)` 新增 `reflectionResult` 入参，并在提示词中输出 `needs_memory/reason/evidence_purposes`
  4. 更新 `SystemPromptBuilderTest` 断言，验证反思决策字段已进入系统提示词
- 状态：已完成
- 实际结果：
  - 开发文档 6.1 的 5 条要求已全部在主链路闭环：结构化对象、反思阶段插入、按反思结果决定记忆加载、反思结果进入 SystemPrompt、失败回退不阻断
  - 本地测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=SystemPromptBuilderTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-29 10:55

- 增强目标：按开发文档 6.2（Step 2/6）补齐“记忆证据追踪”缺口，覆盖记忆/案例/任务/skill 的 retrieved vs used 双视图，并支持 trace 历史落盘
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、`src/main/java/com/memsys/cli/ConversationCli.java`、`src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、`src/main/java/com/memsys/memory/storage/MemoryStorage.java`、`src/main/java/com/memsys/cli/CliRunner.java`、`src/test/java/com/memsys/cli/ConversationCliTest.java`、`src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java`、`src/test/java/com/memsys/memory/storage/MemoryStorageTest.java`、`开发文档.md`
- 实现方案：
  1. `MemoryEvidenceTrace` 升级为结构化双集合：`retrievedInsights/usedInsights`、`retrievedExamples/usedExamples`、`loadedSkills/usedSkills`、`retrievedTasks/usedTasks`
  2. `ConversationCli` 在构建 prompt 时采集证据检索结果，并在 LLM 工具执行阶段包装工具调用，记录真实 `usedSkills/usedTasks`
  3. 增加任务证据采集：合并到期任务通知与和当前消息匹配的任务上下文，并注入 `SystemPromptBuilder`
  4. 新增 `memory_evidence_traces.jsonl` 历史存储，回答后每轮落盘 trace；保留最近一轮内存态查询能力
  5. `/memory-debug`、`/memory-review`、`/memory-timeline` 展示升级为“检索 vs 使用”视图
- 状态：已完成
- 实际结果：
  - 6.2 需求四项已闭环：记录记忆/案例/任务/skill；区分 retrieved/used；支持最近一轮查询；支持历史 trace 存储扩展
  - 本地测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=SystemPromptBuilderTest,ConversationCliTest,MemoryStorageTest test`

#### 迭代记录 - 2026-03-29 10:50

- 增强目标：按开发文档 6.3（Step 3/6）提升场景化命令展示可读性，并在开发完成后执行全量测试
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、新增 `src/test/java/com/memsys/memory/model/MemoryEvidenceTraceTest.java`、修改 `开发文档.md`
- 实现方案：
  1. 升级 `MemoryEvidenceTrace.buildDisplaySummary()`：在 `/memory-debug` 输出中新增 `retrieved/used` 各类证据 Top-N 条目列表（insights/examples/skills/tasks）
  2. 新增 `MemoryEvidenceTraceTest` 验证展示文本包含检索与使用条目区块
  3. 开发文档 5.3.3 增补展示要求：明确需要展示 `retrieved` 与 `used` 条目差异
  4. 执行全量测试 `mvn test`
- 状态：已完成
- 实际结果：
  - `/memory-debug` 可直接展示“检索了什么、最终用了什么”的具体证据条目，终端展示可解释性提升
  - 全量测试通过：`mvn test`（Tests run: 69, Failures: 0, Errors: 0, Skipped: 1）

#### 迭代记录 - 2026-03-29 10:53

- 增强目标：按 Step 4/6 修复现存问题，优先解决评测模式副作用与展示健壮性
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、`src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、`src/test/java/com/memsys/cli/ConversationCliTest.java`
- 实现方案：
  1. 修复评测链路副作用：`processUserMessageWithMemoryForEval` 保留内存态 trace，但不再落盘 `memory_evidence_traces.jsonl`
  2. 增加回归测试 `processUserMessageWithMemoryForEvalShouldNotPersistEvidenceTrace`，锁定“评测不落盘”行为
  3. 增强 `/memory-debug` 稳定性：`MemoryEvidenceTrace.buildDisplaySummary()` 在 `reflection == null` 时回退为可读默认值，避免 NPE
- 状态：已完成
- 实际结果：
  - 评测链路恢复“无文件副作用”语义，与方法注释一致
  - 定向测试通过：`mvn -q -Dtest=ConversationCliTest,MemoryEvidenceTraceTest test`
  - 全量测试通过：`mvn test`（Tests run: 70, Failures: 0, Errors: 0, Skipped: 1）

#### 迭代记录 - 2026-03-29 10:57

- 增强目标：按 Step 5/6 完成 code review，并修复当前审查发现的问题
- 审查范围：`ConversationCli`、`CliRunner`、`MemoryEvidenceTrace` 及作用域相关 CLI 行为
- 发现问题与修复：
  1. 风险问题：`CliRunner` 多处直接访问 `trace.reflection()`，在异常 trace 数据下可能触发 NPE，导致 `/memory-timeline`、`/memory-review`、`/memory-report` 展示链路中断
     - 修复：新增 `traceNeedsMemoryLabel()/traceReason()` 空安全方法，统一替换三处展示逻辑
  2. 一致性问题：命令自动补全 `buildCompletionWords()` 未绑定当前 `activeScope`，在团队/个人作用域切换后可能出现补全内容偏差
     - 修复：补全读取 `skill/memory` 时增加 `MemoryScopeContext.useScope(activeScope)` 绑定
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`
- 状态：已完成
- 实际结果：
  - 展示命令在反思数据缺失场景下可稳定降级输出，不再依赖反思对象必然存在
  - CLI 自动补全与当前作用域保持一致
  - 全量测试通过：`mvn test`（Tests run: 70, Failures: 0, Errors: 0, Skipped: 1）

#### 迭代记录 - 2026-03-29 11:32

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐“最近一轮 trace 查询”在重启后的可用性
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`
- 实现方案：
  1. `ConversationCli.getLastEvidenceTrace()` 改为“内存态优先，缺失时回退读取 `memory_evidence_traces.jsonl` 最后一条”
  2. 新增 trace 反序列化与字段归一化逻辑，兼容 `reflection`/`retrieved_*`/`used_*` 字段缺失或格式不规范场景
  3. 新增回归测试 `getLastEvidenceTraceShouldFallbackToPersistedTraceWhenInMemoryTraceMissing`，验证仅有落盘数据时仍可查询最近 trace
  4. 同步更新开发文档 6.2 完成标准，明确“内存 + 持久化回退”的查询约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 等依赖“最近一轮 trace”的展示链路在会话重启后仍可读取上一轮证据记录
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=ConversationCliTest,MemoryEvidenceTraceTest,MemoryStorageTest test`

#### 迭代记录 - 2026-03-29 11:33

- 增强目标：执行 Step 3/6（6.3）收尾验证，开发完成后进行全量测试回归
- 涉及文件：修改 `开发实现process.md`
- 实现方案：
  1. 核查 6.3 命令清单路由是否完整：`/memory-debug`、`/memory-timeline`、`/memory-review`、`/memory-report`、`/memory-scenes`、`/tasks`
  2. 执行全量测试 `mvn test` 做项目级回归
- 状态：已完成
- 实际结果：
  - 6.3 命令路由在 `CliRunner` 均已接入
  - 全量测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn test`
  - 测试统计：`Tests run: 71, Failures: 0, Errors: 0, Skipped: 1`（`RealApiE2ETest` 为跳过态）

#### 迭代记录 - 2026-03-29 11:35

- 增强目标：执行 Step 4/6（修复存在的问题），提升证据 trace 持久化回读的健壮性
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发实现process.md`
- 实现方案：
  1. 修复持久化 trace 回读字段规范化问题：避免 `null` 被反序列化成字符串 `"null"` 污染 `/memory-debug` 展示
  2. 在 `ConversationCli` 新增统一文本归一化逻辑，应用到 `user_message`、`reflection.reason`、`used_evidence_summary` 与字符串列表字段
  3. 新增回归测试 `getLastEvidenceTraceShouldNormalizeNullLikeFieldsFromPersistedTrace`，覆盖 `null`/`"null"` 混合输入场景
  4. 执行定向与全量回归，确认无回归
- 状态：已完成
- 实际结果：
  - `/memory-debug` 读取历史 trace 时不再出现 `"null"` 文本污染
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=ConversationCliTest test`
  - 全量测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn test`
  - 测试统计：`Tests run: 72, Failures: 0, Errors: 0, Skipped: 1`

#### 迭代记录 - 2026-03-29 11:37

- 增强目标：执行 Step 5/6（code review + 问题修复），修复当前审查发现的展示和证据判定缺陷
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`、`src/main/java/com/memsys/cli/ConversationCli.java`、`src/test/java/com/memsys/cli/ConversationCliTest.java`、`开发实现process.md`
- 审查发现与修复：
  1. 展示语义问题：`/memory-review` 在 `traceNeedsMemoryLabel=unknown` 时被错误映射为“`不需要记忆`”
     - 修复：三态展示（`是 -> 需要记忆`、`否 -> 不需要记忆`、`unknown -> 未知`）
  2. 一致性问题：Evidence purpose 匹配大小写敏感，`PERSONALIZATION` 等枚举值会被漏判，导致 `usedInsights/usedExamples/usedTasks` 统计失真
     - 修复：`ConversationCli.EvidenceCollector.finalizeUsedEvidence` 对 `evidence_purposes` 统一做 `trim + lowercase` 再匹配
  3. 回归保障：新增 `processUserMessageShouldTreatUppercaseReflectionPurposesAsValid`，锁定大小写兼容行为
- 状态：已完成
- 实际结果：
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=ConversationCliTest test`
  - 全量测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q test`

#### 迭代记录 - 2026-03-29 11:41

- 增强目标：执行 Step 6/6（搜索+思考后新增实用功能），为记忆系统补齐“证据质量洞察”闭环
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`、新增 `src/test/java/com/memsys/memory/MemoryTraceInsightServiceTest.java`、修改 `README.md`、修改 `开发文档.md`、修改 `开发实现process.md`
- 设计思路（收敛）：
  1. 搜索现有能力，发现 `MemoryTraceInsightService` 已实现但未被 CLI 暴露，存在“有统计无入口”的可用性缺口
  2. 新增命令 `/memory-insights [limit]`，直接消费 `memory_evidence_traces.jsonl` 生成可执行洞察
  3. 输出结构统一为“样本窗口 -> 反思命中 -> 四类证据使用率 -> 高频模式 -> 优化建议”
  4. 将命令纳入帮助/补全/footer 快捷栏，降低使用门槛
- 功能增强点：
  1. 新增 `showMemoryInsights(int limit)` 展示方法
  2. 新增参数校验：`/memory-insights [limit>0]`
  3. 命令注册与描述更新：支持自动补全与帮助页展示
  4. 新增 `MemoryTraceInsightServiceTest`，验证空样本与聚合统计行为
- 状态：已完成
- 实际结果：
  - 新增命令可用于持续优化记忆系统质量，而不只是查看单轮 trace
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryTraceInsightServiceTest,ConversationCliTest test`
  - 文档已同步：`README.md` 命令表、`开发文档.md`（5.9.1 与命令清单）

#### 迭代记录 - 2026-03-29 11:46

- 增强目标：按 Step 1/6（6.1）复核并加固 Memory Reflection 调用链的失败回退能力
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发实现process.md`
- 问题与修复：
  1. 风险问题：当 `MemoryReflectionService` 不可用（空依赖）时，主链路会触发 NPE，违背“反思失败不阻断回答”要求
  2. 修复方案：在主链路与评测链路反思阶段增加空服务保护，服务缺失时直接使用 `ReflectionResult.fallback()`
  3. 回归保障：新增测试 `processUserMessageShouldFallbackWhenReflectionServiceUnavailable`
- 状态：已完成
- 实际结果：
  - 反思服务不可用场景下仍可正常回复，且反思结果回退为 `reflection_failed_fallback`
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=SystemPromptBuilderTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-29 11:50

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），提升 trace 查询的可操作性与调试效率
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `README.md`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 扩展 `/memory-debug` 命令为 `/memory-debug [N]`，支持查看最近 N 轮 evidence trace（最新在前）
  2. 新增历史展示视图：每条 trace 输出 `needs_memory/reason/user_message` 与 `retrieved vs used` 计数摘要
  3. 保持兼容：无参数仍展示最近一轮详细视图；无历史时回退到内存态最近一轮
  4. 更新命令描述与开发文档，明确支持“窗口化 trace 查询”
- 状态：已完成
- 实际结果：
  - trace 调试从“单轮”升级为“可回看最近 N 轮趋势”，更适合定位检索与使用偏差
  - 文档已同步更新：`README.md` 命令表、`开发文档.md`（5.6.2 与 6.2 完成标准）

#### 迭代记录 - 2026-03-29 11:49

- 增强目标：执行 Step 3/6（开发完成后全量测试回归）
- 涉及文件：修改 `开发实现process.md`
- 实现方案：
  1. 执行全量测试 `mvn test`
  2. 校验关键链路（Memory Reflection、Evidence Trace、任务、IM 解析、工具编排）是否有回归
- 状态：已完成
- 实际结果：
  - 全量测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn test`
  - 测试统计：`Tests run: 76, Failures: 0, Errors: 0, Skipped: 1`（`RealApiE2ETest` 跳过）

#### 迭代记录 - 2026-03-29 11:52

- 增强目标：执行 Step 4/6（修复存在的问题），修复 `/memory-debug [N]` 历史视图中的反思字段误导展示
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 问题与修复：
  1. 问题现象：历史 trace 中若缺失 `reflection.needs_memory` 字段，CLI 会按默认 `false` 渲染为“否”，与真实语义“未知”不一致
  2. 修复方案：`showMemoryDebugHistory(int limit)` 改为三态渲染：`是/否/unknown`；仅在字段存在时解析布尔值
  3. 兼容策略：保留 `reason` 缺省回退 `reflection_missing`，避免旧 trace 数据展示中断
- 状态：已完成
- 实际结果：
  - `/memory-debug [N]` 对旧数据和缺字段数据展示更准确，不再把“缺失”误报成“不需要记忆”
  - 回归测试通过：
    - `export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=ConversationCliTest,MemoryTraceInsightServiceTest test`
    - `export JAVA_HOME=$(/usr/libexec/java_home) && mvn test`

#### 迭代记录 - 2026-03-29 11:54

- 增强目标：执行 Step 5/6（code review + 问题修复），修复反思三态在不同展示命令中的语义不一致问题
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`、新增 `src/test/java/com/memsys/cli/CliRunnerTest.java`、修改 `开发实现process.md`
- 审查发现与修复：
  1. 严重度：中；问题：`/memory-report` 将 `unknown` 误映射为“不需要记忆”，与 `/memory-review`、`/memory-debug` 三态语义不一致，可能误导调试判断
     - 修复：新增统一映射方法 `describeNeedsMemoryLabel(...)`，将 `是/否/unknown` 分别映射为 `需要记忆/不需要记忆/未知`
  2. 严重度：中；问题：`/memory-debug [N]` 对非法 `needs_memory` 值（如 `"null"`、`"N/A"`）会降级为“否”，存在误判
     - 修复：新增 `needsMemoryLabelFromRaw(...)`，仅对合法布尔语义值输出 `是/否`，其余统一输出 `unknown`
  3. 回归保障：新增 `CliRunnerTest`，覆盖三态文案映射和非法值回退
- 状态：已完成
- 实际结果：
  - 三个命令的反思语义保持一致，`unknown` 不再被误报为“不需要记忆”
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=CliRunnerTest,ConversationCliTest test`
  - 全量测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn test`
  - 测试统计：`Tests run: 78, Failures: 0, Errors: 0, Skipped: 1`

#### 迭代记录 - 2026-03-29 11:58

- 增强目标：执行 Step 6/6（搜索+思考后新增实用功能），把 `/memory-insights` 从“静态统计”升级为“趋势诊断”
- 涉及文件：修改 `src/main/java/com/memsys/memory/MemoryTraceInsightService.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/memory/MemoryTraceInsightServiceTest.java`、修改 `README.md`、修改 `开发文档.md`、修改 `开发实现process.md`
- 搜索与思考结论：
  1. 现有能力已能给出整体使用率，但难以回答“最近一段时间是否在退化”
  2. 对记忆系统调优来说，“趋势变化”比“单点均值”更实用，能更快定位 prompt/检索策略回归
  3. 反思字段质量本身也是系统健康度指标，需显式暴露 `needs_memory` 缺失/非法占比
- 实现方案：
  1. `MemoryTraceInsightService` 新增窗口趋势分析：将样本拆分为前后半窗口，输出 `memory_loaded` 与四类证据使用率的 `previous/recent/delta`
  2. 新增 `unknownNeedsMemoryRate`，统计 `reflection.needs_memory` 缺失或非法值比例，并纳入建议生成逻辑
  3. `CliRunner.showMemoryInsights` 展示增强：新增 `unknown` 比例与“趋势对比”区块（pp 变化）
  4. 测试补齐：`MemoryTraceInsightServiceTest` 新增趋势与 unknown 比例回归用例
- 状态：已完成
- 实际结果：
  - `/memory-insights` 可直接回答“最近质量变好还是变差”，支持记忆系统持续调优闭环
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryTraceInsightServiceTest,CliRunnerTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-29 12:02

- 增强目标：执行 Step 1/6（6.1 Memory Reflection 调用链），补齐反思结果规范化，提升可解释性与稳定性
- 涉及文件：修改 `src/main/java/com/memsys/memory/MemoryReflectionService.java`、新增 `src/test/java/com/memsys/memory/MemoryReflectionServiceTest.java`、修改 `开发实现process.md`
- 问题与修复：
  1. 问题：反思返回 `reason` 为空时，提示词中缺少解释文本，不满足“调用记忆时可输出自然语言原因”的稳定性要求
     - 修复：`MemoryReflectionService` 增加默认 reason 回退（需要记忆/不需要记忆两种文案）
  2. 问题：`evidence_purposes` 可能包含大小写/空白/未知值，导致决策链路噪声
     - 修复：新增 purpose 归一化（`trim + lowercase + KNOWN_PURPOSES 白名单 + 去重`）
  3. 问题：`needs_memory=false` 时仍可能携带用途列表，语义不一致
     - 修复：在规范化阶段强制清空用途列表
  4. 回归保障：新增 `MemoryReflectionServiceTest` 覆盖“默认 reason + purpose 白名单 + false 场景清空用途”
- 状态：已完成
- 实际结果：
  - 反思输出在异常/弱质量模型结果下仍保持结构化、可解释和一致语义
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryReflectionServiceTest,ConversationCliTest,SystemPromptBuilderTest test`

#### 迭代记录 - 2026-03-29 12:04

- 增强目标：执行 Step 2/6（6.2 记忆证据追踪），提升 `/memory-debug` 对证据“有效使用程度”的可读性
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/memory/model/MemoryEvidenceTraceTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `MemoryEvidenceTrace.buildDisplaySummary()` 新增覆盖率输出：`Insights/Examples/Skills/Tasks` 的 `used/retrieved` 百分比
  2. `/memory-debug [N]` 历史视图新增同一组覆盖率指标，和检索/使用计数并排展示
  3. 新增回归断言：`MemoryEvidenceTraceTest` 校验覆盖率文本（例如 `Insights 50.0%`）稳定输出
  4. 同步开发文档 6.2 完成标准，明确覆盖率展示要求
- 状态：已完成
- 实际结果：
  - 证据追踪从“数量对比”升级为“数量 + 覆盖率”，可更快发现“检索有结果但回答未使用”的链路问题
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryEvidenceTraceTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-29 12:07

- 增强目标：执行 Step 4/6（修复存在的问题），修复证据覆盖率在“未检索”场景下的误导展示
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/memory/model/MemoryEvidenceTraceTest.java`、修改 `开发实现process.md`
- 问题与修复：
  1. 问题：当某类证据 `retrieved=0` 时，覆盖率被显示为 `0.0%`，容易被误读为“检索到了但没用上”
  2. 修复：覆盖率改为 `n/a`（未检索），仅在 `retrieved>0` 时显示百分比
  3. 影响范围：`/memory-debug` 单轮详情与 `/memory-debug [N]` 历史视图一致生效
  4. 回归保障：新增 `MemoryEvidenceTraceTest.buildDisplaySummaryShouldShowNaWhenNoRetrievedEvidence`
- 状态：已完成
- 实际结果：
  - 证据调试语义更准确，避免“0.0%”与“未检索”混淆
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryEvidenceTraceTest,CliRunnerTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-29 12:10

- 增强目标：执行 Step 5/6（code review + 问题修复），修复覆盖率格式受系统 Locale 影响的不一致问题
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/memory/model/MemoryEvidenceTraceTest.java`、修改 `开发实现process.md`
- 审查发现与修复：
  1. 严重度：中；问题：覆盖率字符串使用默认 Locale，部分环境会输出 `50,0%`（逗号小数），导致展示与自动解析不一致
     - 修复：覆盖率格式统一改为 `Locale.ROOT`，固定输出 `50.0%` 样式
  2. 回归保障：新增 `buildDisplaySummaryShouldUseDotDecimalRegardlessOfDefaultLocale`，在 `Locale.FRANCE` 下校验仍输出点号小数
- 状态：已完成
- 实际结果：
  - 覆盖率展示在不同操作系统/语言环境下保持一致
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryEvidenceTraceTest,CliRunnerTest test`

#### 迭代记录 - 2026-03-29 12:16

- 增强目标：执行 Step 6/6（搜索+思考后新增实用功能），为 `/memory-insights` 增加“用途诊断”能力
- 涉及文件：修改 `src/main/java/com/memsys/memory/MemoryTraceInsightService.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/memory/MemoryTraceInsightServiceTest.java`、修改 `README.md`、修改 `开发文档.md`、修改 `开发实现process.md`
- 搜索与思考结论：
  1. 当前洞察可看总体使用率与趋势，但无法回答“具体哪类 purpose 场景效果差”
  2. 记忆系统调优通常按场景（personalization/continuity/followup 等）进行，缺少 purpose 维度会导致优化动作不精准
  3. 最小增量方案：在现有 trace 聚合上增加 `purpose -> 使用率` 统计，并复用现有 CLI 展示链路
- 实现方案：
  1. `MemoryTraceInsightService` 新增 `PurposeInsight` 聚合：按 purpose 输出样本数、memory_loaded 率、四类证据使用率
  2. `InsightReport` 增加 `topPurposeInsights` 字段
  3. `CliRunner.showMemoryInsights` 新增“用途诊断（按 evidence_purpose）”区块
  4. 测试补齐：`MemoryTraceInsightServiceTest` 增加 purpose 诊断断言（样本、加载率、insight 使用率）
- 状态：已完成
- 实际结果：
  - `/memory-insights` 可从“总体统计”升级到“按场景诊断”，直接指导 prompt/检索策略按 purpose 调优
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryTraceInsightServiceTest,ConversationCliTest test`

---

### Phase 9 - 记忆治理、主动服务与多用户统一身份（进行中）

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

#### 迭代记录 - 2026-03-24 02:00

- 增强目标：为 Memory 模型补齐记忆治理字段（status / verification），并更新 MemoryWriteService 支持治理元数据写入，实现 Phase 9 交付项 #1 基础
- 涉及文件：修改 `Memory.java`（新增 MemoryStatus 枚举 + status / verifiedAt / verifiedSource 字段）、修改 `MemoryWriteService.java`（新增带治理参数的 saveMemoryWithGovernance 方法 + 冲突检测）、修改 `MemoryStorage.java`（新增 readPendingExplicitMemories 方法）、修改 `NightlyMemoryExtractionJob.java`（隐式提取启用冲突检测）、修改 `CliRunner.java`（新增 `/memory-governance` 命令 + `/memory-report` 治理摘要）
- 实现方案：
  1. Memory 模型新增 MemoryStatus 枚举（ACTIVE, PENDING, CONFLICT, ARCHIVED）和 status / verifiedAt / verifiedSource 字段
  2. MemoryWriteService 新增 saveMemoryWithGovernance() 方法，支持冲突检测：当 slotName 已存在且内容不同时，标记为 CONFLICT 写入 pending 队列
  3. NightlyMemoryExtractionJob 的隐式提取改用 saveMemoryWithGovernance(detectConflict=true)
  4. MemoryStorage 新增 readPendingExplicitMemories() 方法读取待处理队列
  5. CliRunner 新增 /memory-governance 命令展示治理全貌 + /memory-report 增加治理摘要行
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/memory/model/Memory.java` — 新增 MemoryStatus 枚举（ACTIVE/PENDING/CONFLICT/ARCHIVED）、status 字段、verifiedAt 字段、verifiedSource 字段
  - 修改 `src/main/java/com/memsys/memory/MemoryWriteService.java` — 新增 saveMemoryWithGovernance() 方法（带冲突检测：已有同 slot 且内容不同时写入 pending 队列而非覆盖；显式来源自动标记 user_confirmed 验证；仅 ACTIVE 状态参与 RAG 索引）
  - 修改 `src/main/java/com/memsys/memory/storage/MemoryStorage.java` — 新增 readPendingExplicitMemories() 方法，读取 pending_explicit_memories.jsonl 全部记录
  - 修改 `src/main/java/com/memsys/memory/NightlyMemoryExtractionJob.java` — 隐式提取改用 saveMemoryWithGovernance(detectConflict=true)，不再直接覆盖已有记忆
  - 修改 `src/main/java/com/memsys/cli/CliRunner.java` — 新增 /memory-governance 命令（展示状态分布、待处理队列、验证记录）；/memory-report 增加治理摘要行
- 实际结果：Memory 模型具备完整治理元数据（status/verifiedAt/verifiedSource）；隐式记忆提取不再直接覆盖已有记忆，冲突写入 pending 队列；/memory-governance 可展示记忆治理全貌；现有调用方向后兼容（默认 ACTIVE、不检测冲突）

#### 迭代记录 - 2026-03-24 02:30

- 增强目标：将自然语言定时任务提取接入对话主链路，用户在普通对话中提及"提醒/定时/任务"时异步提取并创建任务，覆盖 Phase 9 #4 和答辩场景 #6
- 涉及文件：修改 `ConversationCli.java`（在异步记忆操作区域新增任务提取调用）
- 实现方案：
  1. 在 ConversationCli.processUserMessage 的异步记忆操作区域，新增 memoryAsync.submit 调用 scheduledTaskService.tryCreateTaskFromMessage
  2. 传递 sourcePlatform/sourceConversationId/sourceSenderId 实现来源追踪
  3. 任务创建成功时 log.info 记录
  4. 提取失败不阻断主链路（异步且 try-catch）
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/cli/ConversationCli.java` — 在异步记忆操作区域（Phase 8 主题切换检测之后）新增 `extract_scheduled_task` 异步任务，调用 scheduledTaskService.tryCreateTaskFromMessage(userMessage, sourcePlatform, sourceConversationId, sourceSenderId)；成功创建时记录 log.info；失败时 log.debug 不阻断主链路
- 实际结果：用户在正常对话中说"提醒我明天上午 9 点开会"等自然语言时，系统会异步通过 LLM 提取任务意图并自动创建 ScheduledTask，存储到 scheduled_tasks.json；任务到期后由 ScheduledTaskReminderJob 自动推送通知到 IM 或 CLI；整个流程不影响主对话响应速度

#### 迭代记录 - 2026-03-24 03:00

- 增强目标：创建 ProactiveReminderService，定时基于用户画像和长期记忆通过 LLM 生成个性化回顾与建议，通过现有 IM/CLI 通道推送，覆盖 Phase 9 #5 和答辩场景 #7
- 涉及文件：新增 `LlmDtos.ProactiveReminderResult` DTO、新增 `Schemas.proactiveReminderResult()` Schema、修改 `LlmExtractionService`（新增 generateProactiveReminder 方法）、新增 `ProactiveReminderService.java`、新增 `ProactiveReminderJob.java`（Spring @Scheduled 定时触发）、修改 `CliRunner.java`（新增 `/proactive-reminders` 命令）
- 实现方案：
  1. 新增 ProactiveReminderResult DTO（should_remind, reminder_text, reminder_type, based_on_memories, suggested_action）
  2. 在 Schemas 中新增对应 JSON Schema（reminder_type 枚举：review/suggestion/follow_up/insight/none）
  3. LlmExtractionService 新增 generateProactiveReminder(userProfileText, recentSummariesText, currentTime) 方法
  4. 新增 ProactiveReminderService，封装"何时生成提醒"和"提醒落盘"的逻辑，含最小间隔保护（4小时）
  5. 新增 ProactiveReminderJob，每天 8:00/12:00/16:00/20:00 检查一次是否应生成主动提醒
  6. CliRunner 新增 `/proactive-reminders` 命令展示最近的主动提醒记录
  7. MemoryStorage 新增 proactive_reminders.jsonl 读写方法
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/llm/LlmDtos.java` — 新增 ProactiveReminderResult DTO（should_remind, reminder_text, reminder_type, based_on_memories, suggested_action）
  - 修改 `src/main/java/com/memsys/llm/schema/Schemas.java` — 新增 proactiveReminderResult() JSON Schema，reminder_type 使用枚举约束
  - 修改 `src/main/java/com/memsys/llm/LlmExtractionService.java` — 新增 generateProactiveReminder() 方法，基于用户画像+会话摘要+当前时间通过 LLM 生成个性化提醒
  - 新增 `src/main/java/com/memsys/memory/ProactiveReminderService.java` — 主动提醒服务核心：4小时最小间隔保护、画像检查、LLM 生成、落盘到 proactive_reminders.jsonl
  - 新增 `src/main/java/com/memsys/memory/ProactiveReminderJob.java` — Spring @Scheduled 定时任务（每天 8/12/16/20 点），调用 ProactiveReminderService 生成提醒
  - 修改 `src/main/java/com/memsys/memory/storage/MemoryStorage.java` — 新增 appendProactiveReminder() 和 readProactiveReminders() 方法
  - 修改 `src/main/java/com/memsys/cli/CliRunner.java` — 注入 ProactiveReminderService；注册 `/proactive-reminders` 命令；showProactiveReminders() 展示提醒历史（类型图标、时间、内容、建议、来源）；`/memory-report` 新增主动提醒摘要行
- 实际结果：系统在每天 8/12/16/20 点自动检查用户画像和最近会话摘要，通过 LLM 判断是否有值得主动提醒的内容；提醒记录落盘到 proactive_reminders.jsonl；用户可通过 `/proactive-reminders` 查看提醒历史；`/memory-report` 可展示主动提醒累计数量和上次提醒时间；4小时最小间隔保护避免过度打扰

#### 迭代记录 - 2026-03-24 03:30

- 增强目标：创建 UserIdentityService 统一身份映射，支持将不同平台（CLI/飞书/Telegram）的用户 ID 映射到统一身份，覆盖 Phase 9 #2 和 #3 基础，以及答辩场景 #8
- 涉及文件：新增 `src/main/java/com/memsys/identity/UserIdentityService.java`、新增 `src/main/java/com/memsys/identity/model/UserIdentity.java`、修改 `MemoryStorage.java`（新增 identity_mappings.json 读写）、修改 `ImRuntimeService.java`（接入身份解析）、修改 `CliRunner.java`（新增 `/identity` 命令）
- 实现方案：
  1. 新增 UserIdentity 数据模型（unified_id, platform_bindings, display_name, created_at）
  2. 新增 UserIdentityService，维护 platform+senderId → unified_id 映射表
  3. MemoryStorage 新增 identity_mappings.json 读写
  4. ImRuntimeService 在处理消息时通过 UserIdentityService 解析统一身份
  5. 支持自动绑定（首次出现的平台用户自动创建身份）和手动绑定（/identity bind）
  6. CliRunner 新增 /identity 命令展示当前身份绑定列表
- 状态：已完成
- 实际修改文件：
  - 新增 `src/main/java/com/memsys/identity/model/UserIdentity.java` — 统一用户身份数据模型（unifiedId, displayName, platformBindings, createdAt），支持平台绑定/查询/检查
  - 新增 `src/main/java/com/memsys/identity/UserIdentityService.java` — 统一身份映射服务：resolveUnifiedId() 解析统一身份（首次出现自动创建）、bindPlatformToIdentity() 手动跨平台绑定、内存缓存+反向索引+持久化到 identity_mappings.json
  - 修改 `src/main/java/com/memsys/memory/storage/MemoryStorage.java` — 新增 identity_mappings.json 初始化、readIdentityMappings()、writeIdentityMappings() 方法
  - 修改 `src/main/java/com/memsys/im/ImRuntimeService.java` — 注入 UserIdentityService，在 handleIncomingAndReply() 中调用 resolveUnifiedId() 解析统一身份（IM 消息到达时自动绑定）
  - 修改 `src/main/java/com/memsys/cli/CliRunner.java` — 注入 UserIdentityService；注册 `/identity` 命令；showIdentityMappings() 展示所有统一身份及其平台绑定（含平台图标、绑定数统计）；注册命令描述
- 实际结果：系统在收到 IM 消息时自动将 platform+senderId 映射到统一身份（首次出现自动创建 UserIdentity 并持久化到 identity_mappings.json）；CLI 默认用户映射为 user_default；用户可通过 `/identity` 查看所有身份绑定关系；支持手动跨平台身份合并（bindPlatformToIdentity）；为后续跨平台共享用户画像和任务奠定基础

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
18. Phase 8 场景化展示 `/memory-scenes` 已落地：按话题聚类展示会话摘要，支持答辩场景 #5 演示。
19. Phase 9 记忆治理基础已落地：Memory 模型新增 MemoryStatus（ACTIVE/PENDING/CONFLICT/ARCHIVED）+ verifiedAt + verifiedSource 字段；MemoryWriteService 新增冲突检测（saveMemoryWithGovernance）；隐式提取启用冲突检测不再直接覆盖；`/memory-governance` 命令可展示治理全貌。
20. Phase 9 自然语言任务提取已接入主链路：ConversationCli 在每轮对话后异步调用 scheduledTaskService.tryCreateTaskFromMessage，用户自然语言中的任务意图可自动提取并创建 ScheduledTask；ScheduledTaskReminderJob 定时检查到期任务并推送通知。
21. Phase 9 主动提醒已落地：ProactiveReminderService + ProactiveReminderJob 每天定时基于用户画像和会话摘要通过 LLM 生成个性化提醒；提醒落盘到 proactive_reminders.jsonl；`/proactive-reminders` 命令可查看历史；`/memory-report` 新增主动提醒摘要行。
22. Phase 9 统一身份映射已落地：UserIdentityService 支持 platform+senderId → unified_id 双向映射；首次出现自动创建身份并持久化到 identity_mappings.json；ImRuntimeService 在消息到达时自动解析统一身份；`/identity` 命令可查看所有身份绑定；支持手动跨平台身份合并。
23. Phase 8 场景化展示增强已补齐：新增 `/memory-review` 一页式复盘命令，聚合展示最近反思证据、会话摘要、治理状态与近期任务，补上开发文档 6.3 命令清单缺口。

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

---

## 9. 答辩演示场景清单

> 每完成一个功能，检查是否覆盖了下方某个演示场景，覆盖则打 ✅。

| # | 演示场景 | 演示方式 | 覆盖 Phase | 状态 |
|---|---------|---------|-----------|------|
| 1 | 记忆提取与存储：正常对话中自动提取事实并持久化 | 对话 → `/what-you-know` 查看画像 | P3/P5 | ✅ |
| 2 | 记忆召回与应用：后续对话自动引用已有记忆 | 多轮对话观察上下文注入 | P5/P6 | ✅ |
| 3 | 记忆反思过程：展示系统"要不要用记忆"的决策链路 | `/memory-debug` | P7 | ✅ |
| 4 | 会话摘要与压缩：长对话自动生成摘要 | `/memory-report` | P8 | ✅ |
| 5 | 场景化时间线：记忆按时间/主题可视展示 | `/memory-timeline`、`/memory-scenes` | P8 | ✅ |
| 6 | 任务管理：通过自然语言创建、查看、管理任务 | `/tasks` | P9 | ✅ |
| 7 | 主动提醒：系统基于记忆主动推送提醒与建议 | 定时触发 + CLI/IM 推送 | P9 | ✅ |
| 8 | 多平台身份统一：CLI 与飞书共享同一用户画像 | 飞书发消息 → CLI `/what-you-know` 可见 | P9 | ✅ |
| 9 | 评测对比：有记忆 vs 无记忆的效果量化对比 | `/eval-run` | P10 | ⬜ |

#### 迭代记录 - 2026-03-31 00:50

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），修复“持久化 trace 回读时 `needs_memory` 缺失被误判为否”的语义偏差，保持 `/memory-debug` 单轮与历史视图三态一致
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`
- 实现方案：
  1. 在 `ConversationCli.parseEvidenceTrace()` 引入可空布尔解析，`reflection.needs_memory` 仅在可明确解析为 true/false 时才构造 `ReflectionResult`
  2. 对缺失/非法值（含 `"null"`）保持 `reflection=null`，由展示层输出 `unknown`，避免默认映射为“否”
  3. 新增回归测试覆盖 legacy trace（缺失 `needs_memory`）回读场景
  4. 同步开发文档 6.2 完成标准，补充三态语义约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 在仅从 `memory_evidence_traces.jsonl` 回读时，不再把缺字段误显示为“不需要记忆”
  - 记忆证据追踪在“内存态/持久化回读/历史窗口”三条路径上的 `needs_memory` 语义保持一致

#### 迭代记录 - 2026-03-31 08:40

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），统一 `/memory-debug` 单轮与历史窗口的 trace 解析路径，消除历史视图与单轮视图字段清洗不一致风险
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli` 新增 `getRecentEvidenceTraces(int limit)`，统一负责“读取持久化 trace + 规范化解析 + 内存态回退”
  2. `CliRunner.showMemoryDebugHistory(int limit)` 改为消费 `MemoryEvidenceTrace` 对象，不再直接读取原始 map 字段
  3. 历史视图的 `needs_memory/reason/user_message/retrieved-used` 展示全部复用同一规范化结果
  4. 新增回归测试覆盖“历史窗口读取也应清洗 null-like 字段与 legacy reflection”的场景
- 状态：已完成
- 实际结果：
  - `/memory-debug [N]` 与 `/memory-debug` 的三态语义和字段清洗行为保持一致
  - 历史 trace 中的 `null` 字符串与缺失字段不会在历史视图中造成误导展示

#### 迭代记录 - 2026-03-31 09:50

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），修复异步落盘延迟下 `/memory-debug [N]` 历史窗口可能“看不到最新一轮”的可用性问题
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli.getRecentEvidenceTraces(int)` 调整为“内存态优先 + 持久化补齐”，先读持久化窗口并解析，再合并 `lastEvidenceTrace`
  2. 新增 trace 去重判定，避免“已落盘 + 内存态”导致同一条记录重复展示
  3. 保持返回顺序为时间顺序，兼容 `CliRunner` 现有历史展示逻辑（最新在前输出）
  4. 新增回归测试覆盖“异步任务已接收但 trace 尚未写盘”的场景，验证历史窗口仍可看到最新一轮
- 状态：已完成
- 实际结果：
  - `/memory-debug [N]` 在落盘延迟阶段也能展示最新一轮证据 trace，不再落后一轮
  - 历史窗口支持去重，避免同一轮 trace 在持久化和内存态同时存在时重复输出

#### 迭代记录 - 2026-03-31 10:15

- 增强目标：执行 Step 1/6（6.1 Memory Reflection 调用链），按开发文档 v4.2 补齐结构化反思决策字段，完善“反思结果进入 Prompt + 稳定回退”闭环
- 涉及文件：修改 `src/main/java/com/memsys/llm/LlmDtos.java`、`src/main/java/com/memsys/llm/schema/Schemas.java`、`src/main/java/com/memsys/memory/model/ReflectionResult.java`、`src/main/java/com/memsys/memory/MemoryReflectionService.java`、`src/main/java/com/memsys/cli/ConversationCli.java`、`src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、以及对应测试文件
- 实现方案：
  1. `MemoryReflectionResult/ReflectionResult` 新增并规范化 `memory_purpose/confidence/retrieval_hint/evidence_types`
  2. `Schemas.memoryReflectionResult()` 增加对应 schema 字段，保留 `evidence_purposes` 兼容既有链路
  3. `MemoryReflectionService` 增加字段归一化与失败回退：`needs_memory=false` 时强制 `memory_purpose=NOT_NEEDED` 且清空用途与证据类型
  4. `ConversationCli` 补齐新字段落盘与回读，并让证据加载判定同时支持 `evidence_types + evidence_purposes`
  5. `SystemPromptBuilder` 在 3.5 节显式注入新增反思字段，确保主提示词可消费完整决策
- 状态：已完成
- 实际结果：
  - Step 1/6 调用链字段与开发文档 v4.2 对齐，反思决策可解释性和稳定性提升
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryReflectionServiceTest,SystemPromptBuilderTest,ConversationCliTest,MemoryEvidenceTraceTest,ImChatGatingE2ETest test`

#### 迭代记录 - 2026-03-31 10:35

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐 legacy trace 兼容解析能力，避免历史回读在“扁平反思字段”下误显示为 `unknown`
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli` 新增 `parseReflection()` 与 `parseReflectionMap()`，先解析 `reflection` 嵌套对象，失败时回退解析顶层字段
  2. 新增 `evidence_purpose` 单数字段兼容，自动归一到 `evidence_purposes`
  3. 保持三态语义：只有 `needs_memory` 可明确解析时才构造 `ReflectionResult`，否则维持 `reflection=null`
  4. 新增回归测试 `getLastEvidenceTraceShouldParseLegacyTopLevelReflectionFields`，覆盖“顶层反思字段 + 无 reflection 对象”回读场景
  5. 同步开发文档 6.2 完成标准，补充 legacy 扁平字段兼容约束
- 状态：已完成
- 实际结果：
  - 历史 trace 在 `reflection` 缺失但顶层存在 `needs_memory/reason` 时可被正确解析并用于 `/memory-debug`
  - legacy 数据兼容与三态语义同时成立，避免兼容修复引入“未知值被误判为否”的回归风险

#### 迭代记录 - 2026-03-31 11:20

- 增强目标：执行 Step 6/6（调研与文档收敛），围绕 Memory-System 构建“内容化产出”方向，补齐从系统数据到答辩/论文内容资产的落地规范
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在开发文档新增“5.10 内容化产出与知识运营”调研结论，明确内容资产来源、优先级与实施原则
  2. 在开发文档新增“6.9 需求七：补齐内容化产出工作流”，定义交付项与验收标准
  3. 将 Step 6/6 的目标收敛为“模板化复盘 + 可复现案例 + 证据链回放”，避免偏离记忆系统主线
- 状态：已完成
- 实际结果：
  - 形成了围绕现有命令与文件资产的内容生产闭环（`/memory-insights`、`/memory-scenes`、`/tasks`）
  - 后续迭代可以按统一模板持续产出周复盘、案例卡与任务闭环卡，直接服务答辩和论文写作

#### 迭代记录 - 2026-03-31 11:55

- 增强目标：继续执行 Step 6/6（调研深化），把“内容化产出”从方向描述升级为可执行规范（模板字段、目录结构、节奏指标）
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 扩展开发文档 5.10：补齐四条内容支柱（质量/场景/闭环/研究）与六类内容资产池优先级
  2. 明确统一模板字段（时间窗口、数据来源、关键指标、证据片段、结论、下一步动作）与目录规范（`.memory/content-assets/**`）
  3. 强化开发文档 6.9：从“三类模板”升级为“五类模板”，补充命名规则、周度产出节奏、回归响应时效约束
  4. 将验收标准从“可生成内容”提升为“可持续运营”：周度产出基线 + 48 小时回归告警闭环
- 状态：已完成
- 实际结果：
  - Step 6/6 从“文档补充”升级为“内容运营规范”，后续迭代可按固定口径持续沉淀
  - 围绕记忆系统的数据资产形成统一产出协议，减少论文、答辩、周报间的口径漂移风险

#### 迭代记录 - 2026-03-31 12:40

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐 legacy trace 在“字符串化 JSON 字段”下的兼容解析能力，避免 `/memory-debug` 展示退化为原始字符串
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli.parseReflection()` 增加 `reflection` 字段为 JSON 字符串时的回读解析分支（`{...}` → 结构化 `ReflectionResult`）
  2. `ConversationCli.normalizeStringList()` 增加 JSON 数组字符串解析（`[...]`），覆盖 `retrieved_* / used_* / loaded_skills` 等列表字段
  3. 保持现有三态语义约束：`needs_memory` 仍需可明确解析才构造 `ReflectionResult`
  4. 新增回归测试 `getLastEvidenceTraceShouldParseStringifiedReflectionAndListFields`，验证字符串化 reflection 与字符串化列表字段可被正确解析
  5. 同步开发文档 6.2 完成标准，新增“字符串化 JSON 兼容”约束
- 状态：已完成
- 实际结果：
  - 历史 trace 即使存在 `"reflection":"{...}"`、`"retrieved_insights":"[...]"` 等 legacy 形态，也可被 `/memory-debug` 正常消费
  - 降低了手工修复/跨版本迁移后 trace 可视化退化风险，提升证据追踪链路的可用性

#### 迭代记录 - 2026-03-31 13:35

- 增强目标：继续执行 Step 6/6（调研深化与文档更新），围绕 Memory-System 形成“可持续内容运营”执行规范，补齐选题矩阵与产能节奏约束
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在开发文档 5.10 新增“内容选题矩阵”，固定 10 类围绕记忆系统主线的高频选题（反思、证据、连续性、治理、任务、主动服务、论文口径等）
  2. 在开发文档 5.10 新增“命令驱动内容流水线（SOP）”，明确采集/草稿/复核/入库四步时序，并新增索引文件建议 `.memory/content-assets/index.md`
  3. 在开发文档 5.10 新增“周执行节奏”，定义周一/周三/周五与双周图表卡产出机制，降低临近答辩突击风险
  4. 强化开发文档 6.9：补充索引维护与选题覆盖约束，并新增“索引可追溯”与“月度内容均衡”验收标准
- 状态：已完成
- 实际结果：
  - Step 6/6 从“模板规范”进一步升级为“选题-生产-复核-留痕”的完整内容运营流程
  - 围绕记忆系统的内容产出具备稳定节奏与覆盖约束，可持续服务开发迭代、答辩演示与论文写作

#### 迭代记录 - 2026-03-31 14:20

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），修复反思服务返回 `null` 时 trace 链路的稳定性缺口，避免 `needs_memory` 语义漂移或空指针风险
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli` 新增 `ensureReflectionResult(...)`，统一兜底 `ReflectionResult`：
     - `use_saved_memories=true` 且反思结果为 `null` 时，回退 `ReflectionResult.fallback()`
     - `use_saved_memories=false` 时固定为 `ReflectionResult.memoryDisabled()`
  2. 在 `processUserMessage(...)` 与 `processUserMessageWithMemoryForEval(...)` 两条链路统一接入该兜底逻辑，保证 normal/eval 一致
  3. 新增回归测试覆盖“反思服务返回 `null`（normal/eval）”场景，验证 `needs_memory` 与 fallback reason 稳定可观测
  4. 同步开发文档 6.2 完成标准，补充 `null` 反思结果的稳定语义约束
- 状态：已完成
- 实际结果：
  - 反思阶段即使返回 `null`，`/memory-debug` 与 trace 持久化仍可获得稳定、可解释的反思结果
  - Step 2/6 在“反思异常 + 证据追踪”联合链路上的健壮性进一步提升，降低线上不可观测风险

#### 迭代记录 - 2026-03-31 15:10

- 增强目标：继续执行 Step 6/6（调研深化与文档更新），围绕 Memory-System 把“内容资产沉淀”扩展为“可发布内容漏斗 + 反馈回流闭环”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在开发文档 5.10 新增“5.10.4 对外内容产品化（30 天执行版）”，补齐内容分层（L1/L2/L3）、30 天选题排期、统一发布模板与 KPI
  2. 在开发文档 6 章节新增“6.10 需求八：补齐内容发布漏斗与反馈回流”，把内容运营要求转成可验收开发需求
  3. 将 Step 6/6 的落地目标从“内部复盘”升级为“内部证据 -> 对外内容 -> 反馈驱动迭代”的完整闭环
- 状态：已完成
- 实际结果：
  - 围绕记忆系统的内容建设从“模板化记录”升级为“可发布、可复测、可回流”的执行体系
  - 后续可直接按 30 天排期持续输出案例内容，并把反馈稳定转化为 backlog 与验证任务

#### 迭代记录 - 2026-03-31 16:05

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），提升 `/memory-debug` 的可诊断性，减少“检索到了但没用上”问题的排查成本
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/memory/model/MemoryEvidenceTraceTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `MemoryEvidenceTrace.buildDisplaySummary()` 补充反思关键字段展示：`memory_purpose`、`confidence`、`retrieval_hint`、`evidence_types`
  2. 新增覆盖率诊断输出：当 `used=0 && retrieved>0` 标记“已检索但未使用”；当 `used/retrieved < 50%` 标记“使用偏低”
  3. `CliRunner.showMemoryDebugHistory()` 同步展示记忆目的、置信度、检索提示，并输出分证据类型的低覆盖诊断
  4. 新增 `MemoryEvidenceTraceTest` 回归用例，覆盖新增字段展示与诊断提示输出
  5. 同步开发文档 6.2 完成标准，新增“反思关键字段 + 低覆盖诊断”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 对“为什么判定需要记忆、检索是否有效利用”的解释更完整
  - 证据追踪从“计数展示”升级为“可诊断展示”，可直接定位低价值召回并驱动后续优化

#### 迭代记录 - 2026-03-31 16:20

- 增强目标：执行 Step 4/6（修复存在的问题），修复反思置信度归一化中的缩放错误，避免提示词出现异常低置信度
- 涉及文件：修改 `src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、修改 `src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java`、修改 `开发实现process.md`
- 问题与修复：
  1. 问题：`normalizeConfidence()` 在 `confidence > 1` 时统一 `/100`，会把轻微越界值（如 `1.2`）错误缩放为 `0.012`
  2. 修复：区分两类异常输入
     - 轻微越界（`1.0~2.0`）按上限钳制为 `1.0`
     - 百分制输入（`2.0~100`）按 `/100` 归一化
     - 非法值/超大值保持安全回退（`NaN -> 0.70`，`>100 -> 1.0`）
  3. 回归保障：新增 `buildSystemPromptShouldNormalizeOutOfRangeConfidence`，覆盖 `1.2 -> 1.00` 与 `87 -> 0.87`
- 状态：已完成
- 实际结果：
  - 修复后提示词中的 `confidence` 字段不再出现不合理的过小值
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=SystemPromptBuilderTest test`
  - 全量测试通过：`./scripts/run-tests.sh -q test`

#### 迭代记录 - 2026-03-31 16:45

- 增强目标：继续执行 Step 6/6（调研深化与文档更新），围绕 Memory-System 从“内容产出规范”升级为“内容增长飞轮 + 实验机制 + 资产治理”三位一体方案
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 扩展开发文档 5.10，新增 `5.10.5/5.10.6/5.10.7`，分别定义内容增长飞轮、内容实验框架、内容资产治理约束
  2. 将内容来源与系统命令进行一对一映射（`/memory-insights`、`/memory-debug [N]`、`/memory-scenes`、`/tasks`），确保每类内容都有固定证据入口
  3. 明确实验字段（`experiment_id/hypothesis/variant/success_metric/result`）与治理字段（`verification_status/stale_after/retraction_note`），降低“只产出不验证”风险
  4. 在开发文档新增 `6.11 需求九`，把“实验频次、索引完整度、过期结论占比、回流数量”转化为可验收标准
- 状态：已完成
- 实际结果：
  - Step 6/6 从“内容运营流程”进一步升级为“增长与治理并重”的执行框架，内容可持续性与可信度约束更完整
  - 围绕记忆系统形成“证据采样 -> 内容产品化 -> 反馈回流 -> 开发验证”的稳定闭环，后续可直接按月度指标跟踪

#### 迭代记录 - 2026-03-31 17:20

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），修复历史 trace 回读时 `confidence` 量纲兼容缺口，避免 `/memory-debug` 展示异常低置信度
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 调整 `ConversationCli.normalizeConfidence(...)`，与 `SystemPromptBuilder` 保持一致：
     - `0~1` 保持原值
     - `1~2` 视为轻微越界并钳制为 `1.0`
     - `2~100` 视为百分制并归一化为 `0~1`
     - 非法/超范围值按安全边界回退
  2. 新增回归测试 `getLastEvidenceTraceShouldNormalizeLegacyConfidenceScales`，验证 `1.2 -> 1.0`、`87 -> 0.87`
  3. 同步开发文档 6.2 完成标准，补充“历史 confidence 量纲兼容”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 对 legacy trace 的置信度展示与主提示词归一化规则一致
  - 规避了历史数据轻微越界值被误缩放到极低置信度的可观测偏差

#### 迭代记录 - 2026-03-31 17:40

- 增强目标：执行 Step 1/6（6.1 Memory Reflection 调用链）防御性加固，避免提示词出现反思语义冲突
- 涉及文件：修改 `src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、修改 `src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java`、修改 `开发实现process.md`
- 实现方案：
  1. `SystemPromptBuilder.normalizeMemoryPurpose(...)` 增强为严格规范化：`needs_memory=false` 时强制输出 `NOT_NEEDED`
  2. 当 `needs_memory=true` 且上游返回非法/冲突 `memory_purpose`（如 `NOT_NEEDED`）时，统一回退到 `CONTINUITY`
  3. 新增回归测试 `buildSystemPromptShouldForceNotNeededPurposeWhenNeedsMemoryIsFalse`，锁定无记忆场景的提示词稳定语义
- 状态：已完成
- 实际结果：
  - Step 1/6 链路在 Prompt 层进一步具备容错能力，即使上游反思字段异常也不会输出矛盾语义
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=SystemPromptBuilderTest,MemoryReflectionServiceTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-31 18:05

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐“二次字符串化 JSON trace”兼容，避免 `/memory-debug` 在历史回读中展示原始转义字符串
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli` 新增 `unwrapJsonString(...)`，统一解包 `\"{...}\"` / `\"[...]\"` 形态字段
  2. `parseReflectionFromJsonText(...)` 与 `parseStringListFromJsonText(...)` 统一先解包再解析，覆盖嵌套字符串化场景
  3. 新增回归测试 `getLastEvidenceTraceShouldParseDoubleStringifiedReflectionAndListFields`，验证单轮回读兼容
  4. 新增回归测试 `getRecentEvidenceTracesShouldParseDoubleStringifiedFieldsInHistoryView`，验证历史窗口与单轮视图一致
  5. 同步开发文档 6.2 完成标准，新增“二次字符串化 JSON 兼容”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 在 legacy 迁移或外部写入导致的双层转义场景下仍可稳定解析 trace
  - 证据追踪链路的跨版本兼容性与可观测性进一步提升

#### 迭代记录 - 2026-03-31 18:40

- 增强目标：继续执行 Step 6/6（调研与文档收敛），围绕 Memory-System 进一步补齐“内容产品化执行细则”，从内容资产扩展到栏目矩阵与 12 周路线
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在开发文档新增 `5.10.8 记忆系统内容产品矩阵`，按受众拆分四条内容线（builder/user/defense/research），并给出固定栏目建议
  2. 在开发文档新增 `5.10.9 12 周内容执行路线`，按 1-4/5-8/9-12 周分阶段设定产出、回流、收敛目标与验收指标
  3. 在开发文档新增 `6.12 需求十`，将内容产品线字段、12 周看板、栏目覆盖检查、L3 复现实验、`demo_ready` 筛选机制转化为可验收开发项
- 状态：已完成
- 实际结果：
  - Step 6/6 从“内容资产沉淀”进一步升级为“栏目化生产 + 周期化执行 + 答辩可用筛选”的闭环体系
  - 后续可直接按 12 周路线执行并跟踪结果，降低内容建设随机性与答辩前集中补料风险

#### 迭代记录 - 2026-03-31 19:50

- 增强目标：继续执行 Step 1/6（6.1 Memory Reflection 调用链）防御性加固，消除 `needs_memory=false` 场景下的提示词语义冲突风险
- 涉及文件：修改 `src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、修改 `src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java`、修改 `开发实现process.md`
- 实现方案：
  1. `SystemPromptBuilder` 新增 `normalizeEvidenceTypes/normalizeEvidencePurposes`，统一清洗反思证据字段（去空白、大小写归一、白名单过滤、去重）
  2. 当 `needs_memory=false` 时强制不输出 `evidence_types/evidence_purposes`，避免与 `NOT_NEEDED` 决策并存造成提示词自相矛盾
  3. 新增回归测试：
     - `buildSystemPromptShouldForceNotNeededPurposeWhenNeedsMemoryIsFalse` 补充断言，验证无记忆场景不再输出证据字段
     - `buildSystemPromptShouldNormalizeEvidenceFieldsWhenNeedsMemoryIsTrue` 验证证据字段归一化行为
- 状态：已完成
- 实际结果：
  - Step 1/6 在 Prompt 层进一步实现“决策与证据字段语义一致”，降低上游异常值透传到主提示词的风险
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=SystemPromptBuilderTest,MemoryReflectionServiceTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-31 20:00

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），统一历史 trace 回读时反思字段归一化规则，消除 `/memory-debug` 的语义漂移
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli.parseReflectionMap(...)` 接入结构化归一化：`memory_purpose/evidence_types/evidence_purposes`
  2. 新增 `normalizeMemoryPurpose/normalizeEvidenceTypes/normalizeEvidencePurposes`：
     - `memory_purpose` 统一大写并白名单过滤；`needs_memory=false` 强制 `NOT_NEEDED`；`needs_memory=true` 且非法值回退 `CONTINUITY`
     - `evidence_types` 统一大写 + 白名单过滤 + 去重
     - `evidence_purposes` 统一小写 + 白名单过滤 + 去重
  3. 当 `needs_memory=false` 时，强制清空证据类型/用途，避免历史 trace 中出现“无需记忆但仍有证据用途”的展示冲突
  4. 新增回归测试：
     - `getLastEvidenceTraceShouldNormalizeReflectionEvidenceFields`
     - `getLastEvidenceTraceShouldDropEvidenceFieldsWhenNeedsMemoryIsFalse`
  5. 同步开发文档 6.2 完成标准，新增“反思字段统一归一化 + 无记忆场景证据清空”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 在历史 trace 回读时与主链路提示词保持同一反思语义规范，减少定位时的认知噪声
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=ConversationCliTest test`

#### 迭代记录 - 2026-03-31 20:25

- 增强目标：执行 Step 4/6（修复存在的问题），修复历史 trace 回读在 `needs_memory=false` 场景下的 `retrieval_hint` 误导展示
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发实现process.md`
- 问题与修复：
  1. 问题：历史 trace 反序列化时直接透传 `retrieval_hint`，即使 `needs_memory=false` 也可能在 `/memory-debug` 显示“检索提示”，造成语义冲突
  2. 修复：`ConversationCli.parseReflectionMap(...)` 接入 `normalizeRetrievalHint(...)`，在 `needs_memory=false` 时强制清空 `retrieval_hint`；`needs_memory=true` 且提示为空时提供稳定默认提示
  3. 回归保障：扩展 `getLastEvidenceTraceShouldDropEvidenceFieldsWhenNeedsMemoryIsFalse`，新增断言验证 `retrieval_hint` 为空
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 在“无需记忆”场景下不再出现检索提示，反思语义与展示一致
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=ConversationCliTest,SystemPromptBuilderTest test`
  - 全量测试通过：`./scripts/run-tests.sh -q test`

#### 迭代记录 - 2026-03-31 21:05

- 增强目标：继续执行 Step 6/6（调研深化），围绕 Memory-System 扩展“更多内容方向”并把调研结论固化为可执行开发文档
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将 `开发文档.md` 版本升级至 `v4.7`，在 `5.10` 追加“扩展内容方向池”与“7 天落地方案”
  2. 新增 8 类补充内容资产（对照实验、误判剖析、证据优化、治理决策、主动服务命中、时间线、答辩快照、论文局限），并绑定固定命令入口
  3. 新增 `6.13 需求十一`，将扩展模板字段、周度看板、失败内容占比与证据复测机制转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“内容资产沉淀”升级为“更多内容类型 + 周节奏执行 + 指标化验收”的落地方案
  - 后续可直接按 Day 1~Day 7 执行并在 `index.md` 追踪内容覆盖、回流与复测状态

#### 迭代记录 - 2026-03-31 21:20

- 增强目标：围绕 Step 1/6（6.1 Memory Reflection 调用链）执行文档对齐复核，确认“结构化决策 -> 主链路反思 -> 按决策加载记忆 -> Prompt 显式消费 -> 失败回退”五项闭环
- 涉及文件：修改 `开发实现process.md`
- 复核结论：
  1. 结构化对象与 schema 已落地：`ReflectionResult`、`LlmDtos.MemoryReflectionResult`、`Schemas.memoryReflectionResult()`
  2. `ConversationCli` 已在主链路与评测链路插入反思阶段，并以 `shouldLoadMemory = useSavedMemories && reflection.needs_memory()` 控制证据加载
  3. `SystemPromptBuilder` 已显式消费反思字段（`needs_memory/memory_purpose/reason/confidence/retrieval_hint/evidence_*`）
  4. 失败与异常路径已具备稳定回退：`ReflectionResult.fallback()` 与 `ReflectionResult.memoryDisabled()`，空服务/空返回/异常均不阻断主链路
- 验证结果：
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=MemoryReflectionServiceTest,SystemPromptBuilderTest,ConversationCliTest test`
  - 本次复核未发现新增代码缺口，Step 1/6 维持完成态

#### 迭代记录 - 2026-03-31 21:45

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 时间戳兼容解析，避免 `/memory-debug [N]` 在跨版本数据下出现大量 `(unknown)` 时间
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli.parseEvidenceTrace(...)` 抽取 `parseTraceTimestamp(...)`，统一解析 trace 时间戳
  2. 解析顺序扩展为：`ISO_LOCAL_DATE_TIME` -> `ISO_OFFSET_DATE_TIME` -> `yyyy-MM-dd HH:mm:ss`，仅在三者均失败时回退 `null`
  3. 新增回归测试 `getRecentEvidenceTracesShouldParseLegacyTimestampFormats`，覆盖 offset 时间戳与 legacy 空格分隔时间戳
  4. 同步开发文档 6.2 完成标准，新增“多格式时间戳兼容”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug [N]` 历史视图在 `2026-03-31T18:30:00+08:00` 与 `2026-03-31 18:31:05` 两类历史时间戳上可稳定展示时间线
  - Step 2/6 在历史 trace 跨版本可读性与可诊断性上进一步增强

#### 迭代记录 - 2026-03-31 22:10

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多可持续内容”，把卡片化产出升级为系列栏目化执行机制
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.8`，在 `5.10` 新增 `5.10.12 Step 6/6 内容扩展蓝图（第二层：栏目化与系列化）`
  2. 固化 6 条系列栏目：`Memory Weekly Benchmark`、`Memory Failure Library`、`Scene Replay Pack`、`Governance Decision Log`、`Proactive Hit Report`、`Defense Snapshot`
  3. 为系列内容新增统一字段：`series_name/episode_no/baseline_ref/delta_summary/decision`，确保可对照、可追踪、可决策
  4. 在开发文档新增 `6.14 需求十二`，将系列栏目频率、基线对照约束、48 小时回流判断、周度执行小结转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“内容卡模板化”进一步升级为“栏目化连续运营”，可稳定覆盖开发、场景、治理、主动服务与答辩素材
  - 文档侧已形成“栏目定义 -> 模板字段 -> 执行频率 -> 验收标准”的完整闭环，后续可直接按周执行并追踪回流效果

#### 迭代记录 - 2026-03-31 22:58

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐“needs_memory=true 但证据字段缺失/非法”场景下的默认兜底，确保 `/memory-debug` 与主提示词语义一致
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli.normalizeEvidenceTypes/normalizeEvidencePurposes` 增加默认回退：当 `needs_memory=true` 且字段为空或归一化后为空时，回退到 `USER_INSIGHT, RECENT_HISTORY` 与 `continuity`
  2. `parseReflectionMap(...)` 调整 `evidence_purposes/evidence_purpose` 解析顺序：先合并原始字段，再统一归一化，避免合法 legacy 单值被默认值覆盖
  3. 新增回归测试 `getLastEvidenceTraceShouldFallbackEvidenceFieldsWhenNeedsMemoryIsTrue`，覆盖“非法证据字段 -> 默认兜底”场景
  4. 保持既有兼容语义：`needs_memory=false` 仍强制清空证据字段，`evidence_purpose` 合法值仍可被正确保留
  5. 同步开发文档 6.2 完成标准，新增“needs_memory=true 时证据字段默认兜底”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 在历史 trace 的异常字段场景下不再展示空证据集合，且与 `SystemPromptBuilder` 的默认反思语义保持一致
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=ConversationCliTest,SystemPromptBuilderTest test`

#### 迭代记录 - 2026-03-31 23:10

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第三层执行方案，将栏目化产出升级为“资产包化 + 回放校验”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.9`，在 `5.10` 新增 `5.10.13 Step 6/6 内容扩展蓝图（第三层：资产包化与回放即内容）`
  2. 新增 5 类资产包（演示剧本/诊断问答/对照实验/论文附录/发布素材），并绑定固定证据入口与命令回放链路
  3. 固化资产包最小字段（`asset_pack_id/source_content_ids/command_bundle/expected_observations/evidence_snapshot`），补齐可追踪、可回放、可复测要求
  4. 在开发文档新增 `6.15 需求十三`，将资产包目录、状态机、7 天复测、周度执行小结转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“内容卡 + 系列栏目”进一步升级为“资产包化执行”，可直接服务答辩演示、论文附录与对外发布复用
  - 文档侧形成“内容卡 -> 系列栏目 -> 资产包”三层内容体系，后续可按索引字段稳定运营并审计回放状态

#### 迭代记录 - 2026-03-31 23:35

- 增强目标：继续执行 Step 1/6（6.1 Memory Reflection 调用链）防御性加固，补齐反思结果在 `ConversationCli` 主链路/评测链路的二次规范化，避免异常字段透传造成语义漂移
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli` 中新增反思结果统一规范化方法，对 `memory_purpose/reason/confidence/retrieval_hint/evidence_types/evidence_purposes` 执行防御性清洗
  2. 主链路与评测链路在 `ensureReflectionResult(...)` 后统一调用规范化方法，保证上游返回异常值时仍满足 Step 1/6 语义约束
  3. 新增回归测试，覆盖“反思服务返回冲突字段/空值字段”场景
- 状态：已完成
- 实际结果：
  - `ConversationCli` 现已在 normal/eval 两条调用链统一执行反思结果二次规范化，避免上游异常值直接进入提示词和证据链
  - 当 `needs_memory=false` 时，反思结果被强制规范为 `NOT_NEEDED` 且清空检索提示与证据字段，语义不再冲突
  - 新增回归测试 `processUserMessageShouldNormalizeMalformedReflectionResultFromService`，验证冲突字段（如 `needs_memory=false` 但携带用途/检索提示）会被稳定纠正
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=ConversationCliTest,SystemPromptBuilderTest,MemoryReflectionServiceTest test`

#### 迭代记录 - 2026-03-31 23:55

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐 `/memory-debug [N]` 历史视图的反思证据字段展示，避免排障时只看见覆盖率但看不到“期望证据类型/用途”
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/cli/CliRunnerTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `CliRunner.showMemoryDebugHistory(...)` 中新增 `证据类型/证据用途` 输出，字段来源于 trace 内反思结果
  2. 提供 `traceEvidenceTypes(...)`、`traceEvidencePurposes(...)` 统一格式化方法，执行去空白与去重，防止历史脏数据污染展示
  3. 新增回归测试 `traceEvidenceTypesShouldJoinAndDeduplicateValues` 与 `traceEvidencePurposesShouldJoinAndDeduplicateValues`，覆盖格式化稳定性
  4. 同步开发文档 6.2 完成标准，新增“历史视图必须展示 evidence 类型/用途”的验收条款
- 状态：已完成
- 实际结果：
  - `/memory-debug [N]` 现在可在历史窗口直接看到 `evidence_types/evidence_purposes`，单轮与历史视图在反思证据语义上更一致
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=CliRunnerTest,MemoryEvidenceTraceTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-31 23:59

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 扩展“更多内容”的第四层方案，将内容建设升级为“问题库化 + 专题化”长期机制
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.11`，在 `5.10` 新增 `5.10.14 Step 6/6 内容扩展蓝图（第四层：问题库化与专题化）`
  2. 新增 6 类内容方向：`Memory-System 100 问`、`反例与失效边界库`、`模块深潜专栏`、`对照实验周报`、`答辩问答弹药包`、`论文章节素材池`
  3. 补齐 14 天冲刺执行方案，并定义索引字段 `topic_cluster/content_form/reuse_target/proof_level/next_refresh_at`
  4. 在开发文档新增 `6.16 需求十四`，将问题库目录、专题目录、过期刷新机制、专题转 backlog 约束转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“内容卡 -> 系列栏目 -> 资产包”进一步升级为“问题库化 + 专题化”第四层执行机制
  - 围绕记忆系统形成“证据复盘 -> 系列产出 -> 资产打包 -> 问题库沉淀 -> 专题回流”的持续内容闭环，可直接服务开发迭代、答辩问答与论文章节组织

#### 迭代记录 - 2026-03-31 13:25

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 在 `snake_case/camelCase` 字段命名差异下的兼容解析，避免 `/memory-debug` 跨版本回读丢字段
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli.parseEvidenceTrace(...)` 增加字段别名读取：`user_message/userMessage`、`memory_loaded/memoryLoaded`、`retrieved_* / used_*` 与对应 camelCase 版本、`used_evidence_summary/usedEvidenceSummary`
  2. `parseReflectionMap(...)` 增加反思字段别名读取：`needs_memory/needsMemory`、`memory_purpose/memoryPurpose`、`retrieval_hint/retrievalHint`、`evidence_types/evidenceTypes`、`evidence_purposes/evidencePurposes`
  3. 新增回归测试 `getLastEvidenceTraceShouldParseCamelCaseLegacyTraceFields`，覆盖 camelCase 历史 trace 的反思字段与证据字段解析
  4. 同步开发文档 6.2 完成标准，新增“snake_case + camelCase 命名兼容”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 在跨版本 trace 字段命名不一致时仍能稳定展示反思语义与检索/使用证据
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=ConversationCliTest,CliRunnerTest test`

#### 迭代记录 - 2026-03-31 14:05

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第五层方案，将内容体系升级为“渠道化分发 + 自动化生成”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.13`，在 `5.10` 新增 `5.10.15 Step 6/6 内容扩展蓝图（第五层：渠道化分发与自动化生成）`
  2. 新增 8 类内容形态：`Memory 日报快照`、`Memory 周报简报`、`修复公告卡`、`基准榜单卡`、`谣言澄清卡`、`成本效率账本卡`、`IM 周度推送包`、`答辩冲刺提纲包`
  3. 固化“采集 -> 组装 -> 审核 -> 分发 -> 回流”自动化最小流水线，并新增索引字段：`distribution_channel/audience_stage/render_template/auto_generated/publish_url/feedback_score/feedback_volume/next_distribution_at`
  4. 在开发文档新增 `6.17 需求十五`，将渠道目录规范、分发节奏、48 小时反馈回流约束与周度执行小结转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“问题库化 + 专题化”进一步升级为“渠道化运营 + 半自动生产”，内容体系可从内部复盘稳定扩展到多渠道分发
  - 文档侧形成“内容生产 -> 渠道分发 -> 反馈回流 -> 开发验证”的完整闭环，可持续服务开发迭代、答辩与论文输出

#### 迭代记录 - 2026-03-31 14:30

- 增强目标：执行 Step 1/6（6.1 Memory Reflection 调用链）语义加固，修复“仅有 memory_purpose 时 evidence 默认值偏离”的问题
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/ReflectionResult.java`、`src/main/java/com/memsys/memory/MemoryReflectionService.java`、`src/main/java/com/memsys/cli/ConversationCli.java`、`src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、`src/test/java/com/memsys/memory/MemoryReflectionServiceTest.java`、`src/test/java/com/memsys/cli/ConversationCliTest.java`、`src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java`
- 实现方案：
  1. 在 `ReflectionResult` 增加基于 `memory_purpose` 的默认映射方法，统一派生 `evidence_purposes/evidence_types`
  2. `MemoryReflectionService` 在反思输出规范化阶段改为“按 purpose 派生默认证据”，避免一律回退 continuity
  3. `ConversationCli` 运行态规范化与历史 trace 回读规范化统一采用同一映射逻辑，保证主链路与 `/memory-debug` 一致
  4. `SystemPromptBuilder` 按 `memory_purpose` 显示默认证据字段，保持提示词语义与运行链路一致
  5. 补充 3 组回归测试，覆盖 `ACTION_FOLLOWUP` 与 `EXPERIENCE_REUSE` 的默认派生行为
- 状态：已完成
- 实际结果：
  - 当 `needs_memory=true` 但 `evidence_purposes/evidence_types` 缺失或非法时，默认值将与 `memory_purpose` 对齐（如 `ACTION_FOLLOWUP -> followup + TASK`）
  - 主链路反思、持久化 trace 回读、提示词展示三条路径默认语义一致，降低证据类型漂移导致的误检索风险

#### 迭代记录 - 2026-03-31 13:41

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐“检索到了但没用上”的样例级可观测性，降低 `/memory-debug` 定位证据浪费的时间成本
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/memory/model/MemoryEvidenceTraceTest.java`、修改 `src/test/java/com/memsys/cli/CliRunnerTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `MemoryEvidenceTrace.buildDisplaySummary()` 增加 `unused_*` 输出（`retrieved - used`），覆盖 Insights/Examples/Skills/Tasks，默认去空白、去重、限量展示
  2. 在 `CliRunner.showMemoryDebugHistory(...)` 增加“未使用证据”预览行，帮助在历史窗口直接定位浪费证据样例
  3. 新增 `CliRunner.previewUnusedEvidence(...)` 统一差集预览逻辑，支持去重与溢出数量提示（`... +N`）
  4. 新增测试 `previewUnusedEvidenceShouldReturnDeduplicatedPreview`、`previewUnusedEvidenceShouldAppendOverflowCount`，并在 `MemoryEvidenceTraceTest` 补充 `unused_*` 展示断言
  5. 同步开发文档 6.2 完成标准，新增“覆盖率异常时需展示未使用证据样例”验收条款
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 在“已检索但未使用/使用偏低”场景下可直接看到未使用证据样例，定位链路从“看比例”提升到“看对象”
  - Step 2/6 诊断维度从计数级扩展到样例级，排查证据浪费时无需手工比对 retrieved/used 两组列表

#### 迭代记录 - 2026-03-31 14:20

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），修复 `retrieval_hint` 在 null-like 值下的语义漂移，避免主链路与 `/memory-debug` 出现空提示或无效提示
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli.normalizeRetrievalHint(...)` 中统一使用 `isNullLike(...)` 判断（覆盖 `null/undefined/n/a/none`），而非仅判断空串
  2. 新增回归测试 `processUserMessageShouldNormalizeNullLikeRetrievalHintWhenNeedsMemory`，覆盖 `needs_memory=true` 且 `retrieval_hint=N/A` 的兜底行为
  3. 同步开发文档 6.2 完成标准，新增 null-like `retrieval_hint` 默认回退约束，确保 trace 展示与提示词语义一致
- 状态：已完成
- 实际结果：
  - 当反思结果返回 null-like `retrieval_hint` 时，系统稳定回退为默认检索提示“优先检索与用户当前问题最相关的历史证据。”
  - 主链路提示词与证据追踪链路对 `retrieval_hint` 的语义保持一致，降低排障误判风险
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=ConversationCliTest,CliRunnerTest,MemoryEvidenceTraceTest test`

#### 迭代记录 - 2026-03-31 15:20

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第六层方案，将内容体系升级为“专题季化 + 跨版本对照评测产品化”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.15`，在 `5.10` 新增 `5.10.16 Step 6/6 内容扩展蓝图（第六层：专题季化与对照评测产品化）`
  2. 新增 8 类专题季资产：`季度状态报告`、`跨版本对照评测集`、`失败模式周历`、`Evidence 质量榜单`、`主动服务命中季报`、`论文图表季更包`、`答辩演示季度脚本包`、`术语与口径手册`
  3. 固化专题季执行机制（周/双周/月/季四层节奏）与新增索引字段：`season_id/season_track/baseline_version/compare_version/metric_delta/evidence_density/reusability_level/season_review_status`
  4. 在开发文档新增 `6.18 需求十六`，将专题季目录规范、跨版本对照约束、失败素材占比、季报收敛机制转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“渠道化分发 + 自动化生成”进一步升级为“专题季资产化运营”，内容可按季度形成可复用知识产品
  - 围绕记忆系统形成“命令证据 -> 对照评测 -> 专题季报告 -> 回流开发验证”的稳定闭环，可持续支撑开发、答辩与论文三条线

#### 迭代记录 - 2026-03-31 16:12

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 在 legacy 布尔字面量下的兼容解析，避免 `/memory-debug` 跨系统数据回读出现 `unknown/false` 误判
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `src/test/java/com/memsys/cli/CliRunnerTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `ConversationCli` 的 persisted trace 解析增强：`parseBoolean/parseOptionalBoolean` 统一兼容 `yes/no/y/n/是/否`
  2. `CliRunner.needsMemoryLabelFromRaw(...)` 同步增强同一组布尔别名，保持历史视图三态映射一致
  3. 新增回归测试 `getLastEvidenceTraceShouldParseYesNoBooleanLegacyFields`，覆盖 `memory_loaded=yes` 与 `needs_memory=y` 场景
  4. 扩展 `needsMemoryLabelFromRawShouldTreatInvalidValueAsUnknown`，补充 `yes/n/是/否` 断言
  5. 同步开发文档 6.2 完成标准，新增“legacy 布尔字面量兼容”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 在跨系统导出的 `yes/no/y/n/是/否` 布尔字段上可稳定解析，不再误降级为 `unknown/false`
  - Step 2/6 在历史 trace 跨来源兼容性上进一步增强，降低调试误判风险

#### 迭代记录 - 2026-03-31 14:36

- 增强目标：执行 Step 4/6（修复存在的问题），修复真实 API E2E 脚本在 `JAVA_HOME` 缺失场景下的错误提示不清晰问题，避免排障停留在 Maven 通用报错
- 涉及文件：修改 `scripts/run-real-api-e2e.sh`、修改 `README.md`、修改 `开发实现process.md`
- 问题与修复：
  1. 问题：`./scripts/run-real-api-e2e.sh` 仅尝试兜底设置 `JAVA_HOME`，当本机无可用 JDK 时会继续执行 `mvn`，最终只暴露通用错误 `The JAVA_HOME environment variable is not defined correctly`
  2. 修复：在脚本中新增 `resolve_java_home()` 并统一探测顺序（`JAVA_HOME` 现值 -> `/usr/libexec/java_home` -> Homebrew `opt` -> Homebrew `Cellar`）；若仍失败则明确报错并退出
  3. 观测增强：E2E 报告头部新增 `JAVA_HOME` 输出，便于跨环境定位
  4. 文档同步：README 的真实 API E2E 章节新增“脚本会自动兜底解析 JAVA_HOME、失败时提前退出”的说明
- 状态：已完成
- 实际结果：
  - `bash -n scripts/run-real-api-e2e.sh` 语法检查通过
  - 回归通过：`./scripts/run-tests.sh -q -Dtest=ConversationCliTest test`
  - Step 4/6 在运行环境错误可诊断性上补齐“提前失败 + 清晰提示”闭环，降低首次联调成本

#### 迭代记录 - 2026-03-31 14:37

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第七层方案，将内容体系升级为“场景赛题化 + 公开挑战集”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.17`，在 `5.10` 新增 `5.10.17 Step 6/6 内容扩展蓝图（第七层：场景赛题化与公开挑战集）`
  2. 新增 8 类挑战型内容资产：`场景挑战卡`、`回归闯关榜`、`误判样本周榜`、`挑战脚本包`、`修复前后对照卡`、`挑战讲解稿`、`外部复测回执卡`、`挑战赛季总览`
  3. 固化挑战集执行机制（周新增/周回放/双周对照/月总览）与新增索引字段：`challenge_id/challenge_scene/expected_outcome/failure_signature/fix_commit_ref/replay_script_ref/external_retest_status/challenge_score`
  4. 在开发文档新增 `6.19 需求十七`，将挑战目录规范、失败样本占比、修复前后对照约束与周度执行小结转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“专题季化与对照评测产品化”进一步升级为“场景赛题化挑战集”，内容可被外部复测和持续对照，提升可验证性与展示说服力
  - 围绕记忆系统形成“挑战场景 -> 回放验证 -> 修复对照 -> 外部复测 -> 回流开发”的新闭环，后续可稳定用于答辩演示与周度迭代评估

#### 迭代记录 - 2026-03-31 17:05

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 在百分号字符串 `confidence` 下的兼容解析，避免 `/memory-debug` 置信度回退默认值
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli.parseOptionalDouble(...)` 增加百分号字符串兼容：支持 `%` 与全角 `％`，先去掉百分号再走统一 `normalizeConfidence(...)` 归一化
  2. 扩展回归测试 `getLastEvidenceTraceShouldNormalizeLegacyConfidenceScales`，新增 `"85%"` 场景并断言解析为 `0.85`
  3. 同步开发文档 6.2 完成标准，新增“百分号字符串置信度兼容”约束
- 状态：已完成
- 实际结果：
  - 历史 trace 中 `confidence="85%"` / `confidence="85 %"` / `confidence="85％"` 可稳定归一化为 `0.85`
  - `/memory-debug` 与 `/memory-debug [N]` 在跨系统导出置信度格式下展示更稳定，减少默认值回退造成的诊断偏差

#### 迭代记录 - 2026-03-31 18:25

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第八层方案，将内容体系升级为“生态协作化 + 共创回流”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.18`，在 `5.10` 新增 `5.10.18 Step 6/6 内容扩展蓝图（第八层：生态协作化与共创回流）`
  2. 新增 8 类协作型内容资产：`复测任务单`、`外部误判回收卡`、`修复收益对照卡`、`协作专题共创卡`、`复测贡献榜`、`答辩外部证明卡`、`论文外部有效性卡`、`共创回流追踪卡`
  3. 固化协作执行机制（周任务批次/失败样本回收/双周修复收益对照/月度贡献与闭环追踪）与新增索引字段：`collab_batch_id/contributor_type/retest_case_count/valid_issue_count/fix_adoption_count/external_proof_level/closure_status/impact_summary`
  4. 在开发文档新增 `6.20 需求十八`，将协作目录规范、失败样本占比、72 小时回流约束与双周执行小结转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“场景赛题化挑战集”进一步升级为“外部协作复测 + 共创回流”机制，内容体系可持续吸收外部样本并反哺开发
  - 围绕记忆系统形成“复测任务 -> 误判回收 -> 修复采纳 -> 外部再验证 -> 答辩/论文复用”的新闭环，进一步增强可验证性与说服力

#### 迭代记录 - 2026-03-31 15:10

- 增强目标：围绕 Step 1/6（6.1 Memory Reflection 调用链）执行文档对齐复核，并补齐主链路级回归用例，确保“按 `memory_purpose` 派生默认证据”在 `ConversationCli -> SystemPromptBuilder` 链路稳定成立
- 涉及文件：修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发实现process.md`
- 实现方案：
  1. 新增测试 `processUserMessageShouldDeriveEvidenceDefaultsFromMemoryPurpose`
  2. 构造 `needs_memory=true + memory_purpose=ACTION_FOLLOWUP + evidence_types/evidence_purposes 非法` 场景
  3. 断言主链路反思结果被规范化为 `evidence_types=TASK, RECENT_HISTORY` 与 `evidence_purposes=followup`
  4. 同时断言系统提示词显式包含上述派生字段，避免“运行时已修正但 Prompt 未消费”的回归
- 状态：已完成
- 实际结果：
  - Step 1/6 关键验收项“默认值按 `memory_purpose` 派生，不统一回退 continuity”已在主链路层增加自动化保护
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=ConversationCliTest test`

#### 迭代记录 - 2026-03-31 22:20

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 在字段别名差异下的兼容解析，避免 `/memory-debug` 将 Skills 检索量误判为 0
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli.parseReflection(...)` 中补充反思对象别名读取：`reflection_result`、`reflectionResult`
  2. 在 `ConversationCli.parseEvidenceTrace(...)` 中补充 Skills 检索字段别名读取：`retrieved_skills`、`retrievedSkills`，与既有 `loaded_skills/loadedSkills` 统一归并
  3. 新增回归测试 `getLastEvidenceTraceShouldParseReflectionAliasAndRetrievedSkillsAlias`，覆盖“反思字段别名 + Skills 检索字段别名 + 证据默认值派生”组合场景
  4. 同步开发文档 6.2 完成标准，新增字段别名兼容约束（`reflection_result/reflectionResult`、`retrieved_skills/retrievedSkills`）
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 在跨系统导出的字段命名差异下可稳定回读反思对象与 Skills 检索列表
  - Skills 覆盖率诊断不再因字段别名差异退化为 `0/N`，Step 2/6 的跨来源 trace 可观测性进一步提升

#### 迭代记录 - 2026-03-31 23:05

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第九层方案，将内容体系升级为“基准化资产 + 公开评测包”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.20`，在 `5.10` 新增 `5.10.19 Step 6/6 内容扩展蓝图（第九层：基准化资产与公开评测包）`
  2. 新增 8 类基准化内容资产：`Memory 基准数据集卡`、`场景标注规范卡`、`评分协议卡`、`回放评测包`、`月度基准公报`、`回归红榜`、`模型/策略对照表`、`可复现认证卡`
  3. 固化基准执行机制（周样本入库/双周回放/月度公报/72 小时回流判定）与新增索引字段：`benchmark_id/dataset_version/protocol_version/runner_env/model_variant/score_summary/reproducibility_badge/benchmark_status`
  4. 在开发文档新增 `6.21 需求十九`，将基准目录规范、对照评测约束、回流时限与双周执行小结转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“生态协作共创回流”进一步升级为“基准化资产运营”，内容可沉淀为长期可比较、可复放、可复验的评测体系
  - 围绕记忆系统形成“样本沉淀 -> 协议评分 -> 回放评测 -> 月报发布 -> 回流开发验证”的稳定闭环，可持续支撑开发、答辩与论文指标一致性

#### 迭代记录 - 2026-03-31 15:39

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），统一 `memory_purpose` 归一化入口并补齐分隔符/命名风格差异兼容，避免 `action-followup` 等输入被误回退为 `CONTINUITY`
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/ReflectionResult.java`、修改 `src/main/java/com/memsys/memory/MemoryReflectionService.java`、修改 `src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、修改 `src/main/java/com/memsys/cli/ConversationCli.java`、新增 `src/test/java/com/memsys/memory/model/ReflectionResultTest.java`、修改 `src/test/java/com/memsys/memory/MemoryReflectionServiceTest.java`、修改 `src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ReflectionResult` 新增统一入口 `normalizeMemoryPurpose(memoryPurpose, needsMemory)`，集中处理 `NOT_NEEDED` 语义与默认回退
  2. 新增 `canonicalMemoryPurpose(...)`：通过“去非字母字符 + 大写化”兼容 `ACTION_FOLLOWUP/action-followup/action_followup/actionFollowup` 等写法
  3. 将 `ConversationCli`、`MemoryReflectionService`、`SystemPromptBuilder` 的本地归一化逻辑收敛到统一入口，避免多处规则漂移
  4. 新增与扩展回归测试，覆盖 hyphen/snake/camel/空值/矛盾值场景，确保主链路与提示词都输出一致枚举值
  5. 同步开发文档 `6.2` 完成标准，新增 `memory_purpose` 输入别名兼容要求
- 状态：已完成
- 实际结果：
  - 反思主链路与证据追踪链路在 `memory_purpose` 格式差异下可稳定归一化，不再因分隔符差异导致语义漂移
  - `ACTION_FOLLOWUP` 默认派生（`TASK + RECENT_HISTORY` / `followup`）在反思服务、系统提示词、CLI 主链路与模型层单测中形成一致性保护

#### 迭代记录 - 2026-03-31 23:40

- 增强目标：围绕 Step 1/6（6.1 Memory Reflection 调用链）执行文档对齐复核，确认“反思结果传入 Prompt + 按决策加载记忆 + 异常稳定回退”在当前代码中持续成立
- 涉及文件：修改 `开发实现process.md`
- 实现方案：
  1. 逐项对照开发文档 `6.1` 完成标准，复核 `ConversationCli -> MemoryReflectionService -> SystemPromptBuilder` 链路
  2. 定向执行回归：`./scripts/run-tests.sh -q -Dtest=ConversationCliTest,SystemPromptBuilderTest,MemoryReflectionServiceTest test`
  3. 核对关键验收点：`needs_memory` 分流、`memory_purpose` 规范化、`evidence_types/evidence_purposes` 按用途派生、`retrieval_hint` 空值回退、反思异常 fallback
- 状态：已完成
- 实际结果：
  - 本轮未发现 Step 1/6 新增代码缺口，调用链保持完成态
  - Step 1/6 关键行为在主链路与提示词层均有自动化测试覆盖，可直接进入下一步（Step 2/6）开发

#### 迭代记录 - 2026-03-31 16:05

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），对齐 `/memory-insights` 与 `/memory-debug` 的 trace 兼容解析规则，避免洞察统计在跨版本/跨来源数据下偏差
- 涉及文件：修改 `src/main/java/com/memsys/memory/MemoryTraceInsightService.java`、修改 `src/test/java/com/memsys/memory/MemoryTraceInsightServiceTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. `MemoryTraceInsightService` 新增统一读取入口，兼容 `snake_case/camelCase` 字段（如 `memory_loaded/memoryLoaded`、`retrieved_* / used_*` 与 camelCase 对应字段）
  2. 反思对象解析新增别名兼容：`reflection`、`reflection_result`、`reflectionResult`，并支持字符串化 JSON 对象
  3. Skills 统计新增别名兼容：`loaded_skills/loadedSkills/retrieved_skills/retrievedSkills`
  4. 列表字段新增字符串化/二次字符串化 JSON 解析（如 `"[]"`、`"\"[... ]\""`），避免统计链路把列表误判为单字符串
  5. 布尔解析补齐 `yes/no/y/n/是/否`，用于 `memory_loaded` 与 `needs_memory` 聚合统计
  6. 新增测试 `analyzeRecentTracesShouldParseAliasAndStringifiedTraceFields`，覆盖字段别名 + 字符串化 JSON + legacy 布尔字面量组合场景
  7. 在开发文档 `6.2` 完成标准新增第 26 条，明确 `/memory-insights` 与 `/memory-debug` 兼容解析一致性约束
- 状态：已完成
- 实际结果：
  - `/memory-insights` 在历史 trace 的命名差异、字符串化字段和 legacy 布尔值场景下可稳定统计，不再出现 skills 检索量或 needs_memory 比率异常漂移
  - Step 2/6 从“调试视图可读”扩展到“洞察报表可比”，降低跨版本数据分析误判风险
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=MemoryTraceInsightServiceTest test`

#### 迭代记录 - 2026-03-31 16:30

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 在 epoch 时间戳（秒/毫秒）格式下的兼容解析，避免 `/memory-debug [N]` 在跨来源数据上出现 `(unknown)` 时间
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli.parseTraceTimestamp(...)` 中新增 epoch 时间戳解析分支，支持 `Number` 与数字字符串
  2. 新增 `parseEpochTimestamp(...)` 解析逻辑：`10+` 位秒级时间戳与 `13+` 位毫秒级时间戳统一转换为本地 `LocalDateTime`
  3. 保持原有时间戳兼容链路不变（`ISO_LOCAL_DATE_TIME` / `ISO_OFFSET_DATE_TIME` / `yyyy-MM-dd HH:mm:ss`），仅在这些格式不命中时走 epoch 解析
  4. 新增测试 `getRecentEvidenceTracesShouldParseEpochTimestampFormats`，覆盖“秒级数字 + 毫秒级字符串”双场景
  5. 同步开发文档 `6.2` 完成标准第 16 条，补充 epoch 秒/毫秒兼容要求
- 状态：已完成
- 实际结果：
  - `/memory-debug [N]` 历史视图在跨系统导出的 epoch 时间戳场景下可稳定显示时间，不再退化为 `(unknown)`
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=ConversationCliTest test`

#### 迭代记录 - 2026-03-31 23:58

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第十层方案，将内容体系升级为“记忆产品化内容 + 演示资产库”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.22`，在 `5.10` 新增 `5.10.20 Step 6/6 内容扩展蓝图（第十层：记忆产品化内容与演示资产库）`
  2. 新增 8 类产品化内容资产：`记忆能力说明卡`、`一分钟场景演示卡`、`失败到修复故事卡`、`指标解读讲义卡`、`答辩问答速查卡`、`论文图表说明卡`、`版本差异发布卡`、`对外协作邀请卡`
  3. 固化产品化执行机制（周快演示/双周问题解答/月对外发布）与新增索引字段：`product_card_id/audience_type/scenario_script_ref/qa_bundle_ref/demo_ready/proof_level/last_replay_at/publish_window`
  4. 在开发文档新增 `6.22 需求二十`，将产品化目录规范、`demo_ready` 回放约束、公开发布可信约束与双周执行小结转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“基准化资产与公开评测包”进一步升级为“面向受众的产品化内容资产库”，可直接服务开发沟通、答辩演示、论文撰写与对外交流
  - 围绕记忆系统形成“证据采集 -> 资产编排 -> 回放校验 -> 发布分发 -> 反馈回流”的可持续内容闭环，后续可按索引字段稳定运营和审计

#### 迭代记录 - 2026-03-31 23:59

- 增强目标：继续执行 Step 1/6（6.1 Memory Reflection 调用链）健壮性加固，补齐 `evidence_types/evidence_purposes` 在分隔符与命名风格差异下的归一化兼容，避免语义正确输入被误判为非法后回退默认值
- 涉及文件：修改 `src/main/java/com/memsys/memory/model/ReflectionResult.java`、修改 `src/main/java/com/memsys/memory/MemoryReflectionService.java`、修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/main/java/com/memsys/prompt/SystemPromptBuilder.java`、修改 `src/test/java/com/memsys/memory/MemoryReflectionServiceTest.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ReflectionResult` 增加统一证据字段标准化入口，兼容 `recent-history/recentHistory`、`follow-up/followUp` 等写法
  2. 将 `MemoryReflectionService`、`ConversationCli`、`SystemPromptBuilder` 的证据类型/用途归一化逻辑统一改为调用该入口，避免多处规则漂移
  3. 补充主链路与反思服务回归测试，覆盖别名输入场景
  4. 同步开发文档 6.1 完成标准，补充证据字段别名兼容约束
- 状态：已完成
- 实际修改文件：
  - 修改 `src/main/java/com/memsys/memory/model/ReflectionResult.java`
  - 修改 `src/main/java/com/memsys/memory/MemoryReflectionService.java`
  - 修改 `src/main/java/com/memsys/cli/ConversationCli.java`
  - 修改 `src/main/java/com/memsys/prompt/SystemPromptBuilder.java`
  - 修改 `src/test/java/com/memsys/memory/model/ReflectionResultTest.java`
  - 修改 `src/test/java/com/memsys/memory/MemoryReflectionServiceTest.java`
  - 修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`
  - 修改 `开发文档.md`
  - 修改 `开发实现process.md`
- 实际结果：
  - Step 1/6 调用链现在可兼容 `evidence_types/evidence_purposes` 的 hyphen/snake/camel 输入（如 `recent-history/recentHistory`、`follow-up/followUp`），并统一归一化为标准枚举值
  - 反思服务、CLI 主链路、System Prompt 三层证据字段归一化规则一致，降低“语义正确但被判非法”导致的默认值误回退风险
  - 定向测试通过：`./scripts/run-tests.sh -q -Dtest=ReflectionResultTest,MemoryReflectionServiceTest,SystemPromptBuilderTest,ConversationCliTest test`
  - 编译通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn compile -q`

#### 迭代记录 - 2026-03-31 16:45

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 在微秒/纳秒 epoch 时间戳下的兼容解析，避免 `/memory-debug [N]` 在跨系统高精度时间格式中出现异常时间
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 扩展 `ConversationCli.parseEpochTimestamp(String)` 的数字匹配范围：从 `10-17` 位提升到 `10-19` 位，覆盖秒/毫秒/微秒/纳秒场景
  2. 扩展 `ConversationCli.parseEpochTimestamp(double)` 的量纲识别阈值：新增微秒（`>=1e15`）与纳秒（`>=1e18`）到毫秒的换算分支
  3. 新增回归测试 `getRecentEvidenceTracesShouldParseEpochMicroAndNanoTimestampFormats`，覆盖“微秒数字 + 纳秒数字字符串”组合场景
  4. 同步开发文档 `6.2` 完成标准，新增“高精度 epoch 时间戳兼容”条款
- 状态：已完成
- 实际结果：
  - `/memory-debug [N]` 历史视图可稳定回读 16-19 位高精度 epoch 时间，不再受跨系统时间单位差异影响
  - Step 2/6 时间线兼容能力从“秒/毫秒”扩展到“秒/毫秒/微秒/纳秒”，降低历史数据导入后的排障成本

#### 迭代记录 - 2026-03-31 23:50

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第十一层方案，将内容体系升级为“选题智能编排 + 质量闸门 + 内容债治理”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.23`，在 `5.10` 新增 `5.10.21 Step 6/6 内容扩展蓝图（第十一层：选题智能编排与质量闸门）`
  2. 新增 8 类运营型内容资产：`选题优先级榜单卡`、`内容债清单卡`、`复测排班卡`、`证据缺口告警卡`、`结论寿命跟踪卡`、`回流收益归因卡`、`自动周刊草稿包`、`发布后质量回执卡`
  3. 固化运营机制（周选题评分/周中质量闸门/周末内容债清理/双周收益归因）与新增索引字段：`topic_score/evidence_completeness/replay_cost/freshness_risk/content_debt_level/qa_gate_status/automation_run_id/roi_tag`
  4. 在开发文档新增 `6.23 需求二十一`，将运营目录规范、闸门准入约束、内容债处理时限与收益归因约束转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“产品化内容资产库”进一步升级为“运营决策层”，内容生产可按评分机制稳定排期，并通过质量闸门控制发布风险
  - 围绕记忆系统形成“选题评分 -> 证据校验 -> 发布准入 -> 反馈回流 -> 收益归因”的可持续闭环，后续可按周度指标持续优化内容投入产出比

#### 迭代记录 - 2026-03-31 17:20

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 在“对象数组证据列表”格式下的兼容解析，避免 `/memory-debug` 与 `/memory-insights` 把证据项展示为原始对象字符串
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/main/java/com/memsys/memory/MemoryTraceInsightService.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `src/test/java/com/memsys/memory/MemoryTraceInsightServiceTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli` 的 trace 列表解析链路新增对象项归一化：当列表项是 `Map` 时优先抽取 `name/title/text/content/id/slot_name/slotName/value`
  2. 在 `MemoryTraceInsightService` 复用同级规则，确保 `/memory-insights` 与 `/memory-debug` 对象列表解析口径一致
  3. 新增 `getLastEvidenceTraceShouldParseObjectListEvidenceFields`，覆盖 `Collection<Map>` 与字符串化 JSON 对象数组的混合场景
  4. 新增 `analyzeRecentTracesShouldParseObjectListEvidenceFields`，覆盖对象数组证据在洞察统计中的 `retrieved/used` 计数一致性
  5. 同步开发文档 `6.2` 完成标准新增第 28 条，明确“对象数组证据列表兼容”约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 历史回读在对象数组格式下可输出稳定可读证据项，不再出现 `{name=...}` 形式噪声
  - `/memory-insights` 与 `/memory-debug` 在跨来源对象列表数据下保持一致统计，降低覆盖率误判风险

#### 迭代记录 - 2026-03-31 17:40

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐历史 trace 在 null-like 证据项下的兼容过滤，避免 `/memory-debug` 与 `/memory-insights` 把噪声值计入覆盖率
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/main/java/com/memsys/memory/MemoryTraceInsightService.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `src/test/java/com/memsys/memory/MemoryTraceInsightServiceTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli.normalizeStringList/normalizeListItem` 增加 null-like 过滤：`null/undefined/n/a/none`（大小写不敏感）在证据列表解析时统一丢弃
  2. 在 `MemoryTraceInsightService.asStringList/normalizeListItem` 复用同级规则，确保 `/memory-insights` 统计不把噪声 token 当作真实证据
  3. 新增 `getLastEvidenceTraceShouldIgnoreNullLikeEvidenceTokens`，覆盖字符串列表、字符串化 JSON 列表与大小写混合 token 的过滤行为
  4. 新增 `analyzeRecentTracesShouldIgnoreNullLikeEvidenceTokens`，覆盖 null-like token 过滤后 `retrieved/used` 计数与用途统计的一致性
  5. 同步开发文档 `6.2` 完成标准新增第 29 条，明确 null-like 证据项过滤约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 历史回读不再展示 `null/undefined/n/a/none` 噪声证据，证据明细更可读
  - `/memory-insights` 与 `/memory-debug` 在 null-like 场景下保持一致统计口径，覆盖率与用途洞察更稳定

#### 迭代记录 - 2026-03-31 17:55

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第十二层方案，将内容体系升级为“课程化训练 + 认证化输出”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.25`，在 `5.10` 新增 `5.10.22 Step 6/6 内容扩展蓝图（第十二层：课程化训练与认证化输出）`
  2. 新增 8 类课程化内容资产：`十分钟上手实验卡`、`场景训练题单卡`、`误判纠偏练习卡`、`助教讲解脚本卡`、`评分量表卡`、`训练营周报卡`、`能力认证回执卡`、`新成员入门路径图`
  3. 固化课程化执行机制（周训练资产/双周回放评分/月训练营周报）与新增索引字段：`training_asset_id/skill_track/difficulty_level/rubric_ref/pass_threshold/cert_status/cert_valid_until/mentor_feedback_ref`
  4. 在开发文档新增 `6.24 需求二十二`，将训练目录规范、认证时效约束、训练反馈回流约束与对外能力声明约束转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“选题智能编排 + 质量闸门”进一步升级为“训练与认证机制”，内容可用于新人培养、答辩演练与对外能力证明
  - 围绕记忆系统形成“证据内容 -> 训练题单 -> 回放评分 -> 认证回执 -> 反馈回流”的持续闭环，降低内容体系只产出不传承的风险

#### 迭代记录 - 2026-03-31 17:30

- 增强目标：围绕 Step 1/6（6.1 Memory Reflection 调用链）执行文档对齐复核，确认当前实现满足最新开发文档 `v4.25` 的完成标准
- 涉及文件：修改 `开发实现process.md`
- 实现方案：
  1. 逐项核查 Step 1/6 五项要求在主链路中的落点：结构化决策对象/schema、`ConversationCli` 反思阶段、按反思结果决定记忆加载、反思结果注入 `SystemPromptBuilder`、异常稳定回退
  2. 核查 `memory_purpose` 与 `evidence_types/evidence_purposes` 的别名归一化与默认值派生是否按用途生效（避免统一回退 continuity）
  3. 执行 Step 1/6 相关定向回归：`ReflectionResultTest`、`MemoryReflectionServiceTest`、`SystemPromptBuilderTest`、`ConversationCliTest`
- 状态：已完成
- 实际结果：
  - 本轮未发现 Step 1/6 新增代码缺口，反思调用链保持完成态
  - 主链路与提示词层在 `needs_memory` 分流、`memory_purpose` 归一化、证据字段默认派生、`retrieval_hint` null-like 回退方面行为一致
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=ReflectionResultTest,MemoryReflectionServiceTest,SystemPromptBuilderTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-31 18:25

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），修复“未使用证据样例”在大小写/空白差异下的误判，避免 `/memory-debug` 诊断噪声
- 涉及文件：修改 `src/main/java/com/memsys/cli/CliRunner.java`、修改 `src/main/java/com/memsys/memory/model/MemoryEvidenceTrace.java`、修改 `src/test/java/com/memsys/cli/CliRunnerTest.java`、修改 `src/test/java/com/memsys/memory/model/MemoryEvidenceTraceTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `CliRunner.previewUnusedEvidence` 中将 `retrieved - used` 比对改为“规范键比较”（trim + 折叠连续空白 + 小写化），并保留原文展示
  2. 在 `MemoryEvidenceTrace.appendUnusedList` 复用同级比较规则，确保单轮 `/memory-debug` 与历史 `/memory-debug [N]` 未使用样例口径一致
  3. 新增 `previewUnusedEvidenceShouldIgnoreCaseAndWhitespaceVariance`，覆盖 `Debug  Skill` vs `debug skill` 误判场景
  4. 新增 `buildDisplaySummaryShouldCompareUnusedEvidenceIgnoringCaseAndWhitespace`，覆盖单轮视图未使用样例去噪行为
  5. 同步开发文档 `6.2` 完成标准新增第 30 条，明确未使用样例比对的格式兼容约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-debug [N]` 在证据文本仅存在大小写或空白差异时不再误报“未使用”，证据浪费诊断更准确
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=CliRunnerTest,MemoryEvidenceTraceTest test`

#### 迭代记录 - 2026-03-31 23:58

- 增强目标：继续执行 Step 6/6（调研与文档更新），围绕 Memory-System 打造“更多内容”的第十三层方案，将内容体系升级为“周主题策展 + 双轨编排 + 跨周复用”
- 涉及文件：修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 将开发文档版本升级至 `v4.27`，在 `5.10` 新增 `5.10.23 Step 6/6 内容扩展蓝图（第十三层：周主题策展与双轨内容编排）`
  2. 新增 8 类策展型内容资产：`周主题策展卡`、`主线结论卡`、`快报短内容卡`、`深度长内容卡`、`主题问答包卡`、`反例补洞卡`、`跨周复用卡`、`主题收官卡`
  3. 固化双轨机制（生产轨 + 复核轨）与跨周复用约束，并新增索引字段：`theme_week/theme_name/track_type/content_tier/anchor_conclusion_id/counterexample_ref/reuse_from_ids/weekly_close_status`
  4. 在开发文档新增 `6.25 需求二十三`，将主题目录规范、双轨编排、跨周复用、周收官与过程留痕转化为可验收条款
- 状态：已完成
- 实际结果：
  - Step 6/6 从“课程化训练与认证化输出”进一步升级为“主题化稳定产能机制”，内容生产从零散扩展到按周策展的连续运营
  - 围绕记忆系统形成“主题定义 -> 主线产出 -> 反例补洞 -> 跨周复用 -> 周收官 -> 反馈回流”的完整闭环，可直接支撑答辩材料与开发迭代双线复用

#### 迭代记录 - 2026-03-31 18:40

- 增强目标：围绕 Step 1/6（6.1 Memory Reflection 调用链）按开发文档 `v4.27` 复核当前项目并完成文档对齐
- 涉及文件：修改 `开发实现process.md`
- 实现方案：
  1. 复核 Step 1/6 五项要求在现有代码中的落点：结构化决策对象/schema、`ConversationCli` 反思阶段、按反思结果控制记忆加载、反思结果注入 `SystemPromptBuilder`、失败稳定回退
  2. 复核完成标准 4/5：`needs_memory=true` 时 `evidence_types/evidence_purposes` 默认值按 `memory_purpose` 派生，且支持 hyphen/snake/camel 别名归一化
  3. 执行 Step 1/6 定向回归：`ReflectionResultTest`、`MemoryReflectionServiceTest`、`SystemPromptBuilderTest`、`ConversationCliTest`
- 状态：已完成
- 实际结果：
  - 当前实现与开发文档 `6.1` 保持一致，本轮未发现需要新增的代码缺口
  - Step 1/6 调用链在主链路、提示词层与回退路径均可稳定闭环
  - 定向测试通过：`JAVA_HOME=$(/usr/libexec/java_home) mvn -q -Dtest=ReflectionResultTest,MemoryReflectionServiceTest,SystemPromptBuilderTest,ConversationCliTest test`

#### 迭代记录 - 2026-03-31 17:42

- 增强目标：继续执行 Step 2/6（6.2 记忆证据追踪），补齐嵌套证据结构（`evidence.retrieved/used.*`）的兼容解析，避免 `/memory-debug` 与 `/memory-insights` 在跨系统 trace 上把覆盖率误判为 0
- 涉及文件：修改 `src/main/java/com/memsys/cli/ConversationCli.java`、修改 `src/main/java/com/memsys/memory/MemoryTraceInsightService.java`、修改 `src/test/java/com/memsys/cli/ConversationCliTest.java`、修改 `src/test/java/com/memsys/memory/MemoryTraceInsightServiceTest.java`、修改 `开发文档.md`、修改 `开发实现process.md`
- 实现方案：
  1. 在 `ConversationCli.parseEvidenceTrace(...)` 新增 `readTraceList(...)` 统一读取入口：优先读取既有顶层字段，缺失时回退读取 `evidence` 嵌套结构
  2. 支持 `evidence.retrieved.*` / `evidence.used.*` 分组，以及 `loaded` 作为检索组别别名；支持 `insights/examples/skills/tasks` 的复数/单数与列表后缀字段
  3. 在 `MemoryTraceInsightService` 复用同级解析规则，保证 `/memory-insights` 与 `/memory-debug` 对嵌套结构统计口径一致
  4. 新增回归测试 `getLastEvidenceTraceShouldParseNestedEvidenceGroups` 与 `analyzeRecentTracesShouldParseNestedEvidenceGroups`，覆盖嵌套检索/使用列表统计
  5. 同步开发文档 `6.2` 完成标准新增第 31 条，明确嵌套证据结构兼容约束
- 状态：已完成
- 实际结果：
  - `/memory-debug` 与 `/memory-insights` 可稳定解析 `evidence.retrieved/used.*` 结构，不再因字段层级差异导致证据计数失真
  - Step 2/6 的跨来源 trace 兼容能力从“字段别名”扩展到“结构别名”，调试与洞察链路一致性进一步提升
  - 定向测试通过：`export JAVA_HOME=$(/usr/libexec/java_home) && mvn -q -Dtest=ConversationCliTest,MemoryTraceInsightServiceTest test`
