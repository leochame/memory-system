## 项目严重缺陷与高优先问题清单（精简版）

### 一、整体判断

- **架构与主流程已跑通**：技术栈、目录结构、存储文件、LLM 接入、Top of Mind 队列、定时任务、临时对话模式等骨架基本正确，可作为 MVP 使用。
- **关键能力存在明显缺失**：隐式记忆与话题分析、用户控制面板、隐私控制入口、冲突确认、测试体系等与设计目标有明显差距，需要作为后续迭代重点。

---

### 二、严重缺陷 / 严重 BUG（必须优先修）

- **1. 隐式记忆与话题分析基本未生效**
  - `LlmClient.extractUserInsights(...)` 和 `LlmClient.analyzeTopics(...)` 目前直接返回空列表，`MemoryExtractor` 只是包装这些空结果。
  - 夜间任务 `NightlyMemoryExtractionJob` 虽会定期运行，但**几乎不会写入新的 User Insights 和 Notable Highlights**，导致隐式记忆模块名存实亡。
  - **改进方向**：为上述两个方法实现真实的 JSON 解析闭环（Prompt → LLM 回复 JSON → Jackson 解析 → 写入 `implicit_memories.json`），并补上基础单元测试。

- **2. 显式记忆冲突时直接覆盖，缺少用户确认**
  - 当新显式记忆的 `slot_name` 已存在时，`ConversationCli.handleExplicitMemory(...)` 只在日志里提示“检测到记忆冲突”，随后**直接覆盖旧记忆**。
  - 这会导致用户偏好或长期档案在无提示的情况下被替换，与“可控记忆/隐私”的产品目标冲突。
  - **改进方向**：在 CLI 中加入交互确认（Y/N），或至少在终端输出“原值 → 新值”，让用户明确决策是否覆写。

- **3. 用户控制面板能力明显不足**
  - 目前 CLI 只支持：`/memories`（列表）、`/delete <槽位名>`（删除）、`/cleanup`（清理）、基础 `/help`、`/exit`。
  - 缺失的关键操作：
    - 编辑已有记忆（虽然 `MemoryManager.editMemory(...)` 已实现）。
    - 通过命令切换 `use_saved_memories` / `use_chat_history` 等全局控制。
    - 手动触发隐式记忆/档案卡更新（例如文档里的 `memory update` 思路）。
  - **改进方向**：最少补齐以下命令：
    - `/edit <槽位名>`：交互式修改记忆内容。
    - `/controls` / `/controls use_memories on|off` / `/controls use_history on|off`：查询和修改全局开关。
    - `/memory-update`：基于最近对话历史主动触发一次隐式记忆/档案卡提取。

- **4. 隐私与“无痕模式”控制入口不完整**
  - 底层已经支持：
    - 临时对话模式（不读写记忆），但只能靠启动参数 `--temporary` / `temp`。
    - 通过 `MemoryManager.setGlobalControl(...)` 关闭记忆和历史使用。
  - 问题在于：
    - 运行中无法通过命令切换临时模式/全局控制，普通用户很难发现和使用这些隐私能力。
  - **改进方向**：
    - 在 `/help` 与欢迎信息中显式说明隐私相关能力。
    - 增加 CLI 命令控制 `global_controls`，并在每次修改后回显当前状态。

- **5. Top of Mind 相关逻辑有偏差，影响上下文质量**
  - `MemoryStorage.getRecentMessages(limit)` 通过 `.limit(limit)` 获取的是**文件最前面的 N 行**，而非“最近 N 条消息”；当历史较长时，传给 LLM 的上下文会偏向更早的内容。
  - `ConversationCli.updateMemoryAccess(...)` 简化为“对所有记忆调用 `updateAccessTime`”，缺少真正的相关性判断，导致所有记忆权重都被频繁刷新，削弱 Top of Mind 队列设计的价值。
  - **改进方向**：
    - 将 `getRecentMessages` 改为返回**最新的 N 条**（截取列表尾部或反向遍历）。
    - 在 `updateMemoryAccess` 中增加最基础的相关性过滤（例如关键词匹配、或调用模型做一轮相似度打分），只更新与当前对话明显相关的记忆。

- **6. 测试体系几乎为空，回归风险极高**
  - `src/test/java/com/example/memorybox/` 目前为空，未实现开发文档中“文件操作、清理策略、冲突检测、Top of Mind 算法、System Prompt 构建”等关键测试。
  - 任何对记忆读写、清理策略、隐私逻辑的改动都缺乏安全网，长期看很容易引入回归 BUG。
  - **改进方向（最小集）**：
    - 为 `MemoryStorage` 写基础单元测试：六个文件的读写、滚动窗口、appendonly 行为。
    - 为 `MemoryManager.cleanupOldMemories(...)`、`updateAccessTime(...)`、`getTopOfMindMemories(...)` 写策略性测试。
    - 为 `ConversationCli.processUserMessage(...)` 写至少一条“正常对话 + 显式记忆写入”的集成测试（可使用 Fake LLM）。

---

### 三、次高优先但重要的问题（可在上一轮之后跟进）

- **A. CLI 未使用配置化清理阈值**
  - `/cleanup` 当前硬编码为 `cleanupOldMemories(100, 30)`，与 `application.yml` 中的 `memory.max-slots`、`memory.days-unaccessed` 不一致。
  - 建议直接读取配置，避免行为和配置漂移。

- **B. “你记得我什么”缺少专门实现**
  - 目前只能靠模型在 Prompt 中自行推理记忆内容，没有专门命令（如 `/what-you-know`）来读取所有记忆并做解释。
  - 对于用户感知记忆系统是否“可靠/可控”影响较大，建议在控制面板补一条此类命令。

---

### 四、简短结论（给下轮开发看的）

- **现在的状态**：架构完整、能跑、可当个人实验/MVP 用，但“记忆系统”的智能与可控体验远未达到设计稿。
- **下一轮开发重点**（按优先级）：
  1. 补齐隐式记忆/话题分析的真实实现（含 JSON 解析与最小测试）。
  2. 加上显式记忆冲突确认、基础 CLI 控制面板（编辑、全局开关、手动更新）、隐私相关命令。
  3. 修正最近消息与 Top of Mind 的关键逻辑，保证上下文质量。
  4. 搭一套最小可用的测试用例，为后续重构保留安全网。