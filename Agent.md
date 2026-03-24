# Workspace Map

## Priority Context Files

1. `.memory/user-insights.md`
   - 用户长期画像正文（只读）
2. `README.md`
   - 项目总览、启动方式、命令入口
3. `开发文档.md`
   - 架构基线与实现规范
4. `开发实现process.md`
   - 当前阶段、里程碑和待办

## On-Demand Files

1. `.memory/skills/*.md`
   - 优先通过 `load_skill(name)` 按名称加载单个 skill
2. `src/main/java/com/memsys/cli/ConversationCli.java`
   - 对话主编排与 Prompt 入口
3. `src/main/java/com/memsys/prompt/SystemPromptBuilder.java`
   - System Prompt 拼装
4. `src/main/java/com/memsys/memory/storage/MemoryStorage.java`
   - `.memory/` 初始化、迁移与读写
5. `src/main/java/com/memsys/skill/SkillService.java`
   - skill 列举、读取、保存
6. `src/main/java/com/memsys/rag/RagService.java`
   - Example/RAG 检索和索引

## Read-Only Shell Loading

1. 文件定位：`rg --files`、`find`、`ls`
2. 关键词检索：`rg -n`、`grep -n`
3. 局部阅读：`sed -n`、`head`、`tail`
4. 全文查看：`cat`

## Boundaries

1. 默认只读探索，按需加载，不全量注入
2. 不在这里维护高频运行态数据
