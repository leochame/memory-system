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
