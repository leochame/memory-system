# Thesis Review

本轮审稿对象是 [毕设文档/毕业论文初稿.md](/Users/leocham/Documents/code/memory-system/毕设文档/毕业论文初稿.md)。仓库里没有 `毕设文档/workflow/02_draft.md`，因此本轮按当前正文与项目实现交叉核对 [开发实现process.md](/Users/leocham/Documents/code/memory-system/开发实现process.md)、[README.md](/Users/leocham/Documents/code/memory-system/README.md)、[开发文档.md](/Users/leocham/Documents/code/memory-system/开发文档.md)、[毕设文档/workflow/01_plan.md](/Users/leocham/Documents/code/memory-system/毕设文档/workflow/01_plan.md)、[src/main/java/com/memsys/cli/ConversationCli.java](/Users/leocham/Documents/code/memory-system/src/main/java/com/memsys/cli/ConversationCli.java)、[src/main/java/com/memsys/prompt/SystemPromptBuilder.java](/Users/leocham/Documents/code/memory-system/src/main/java/com/memsys/prompt/SystemPromptBuilder.java)、[src/main/java/com/memsys/rag/RagService.java](/Users/leocham/Documents/code/memory-system/src/main/java/com/memsys/rag/RagService.java)、[src/main/java/com/memsys/skill/SkillService.java](/Users/leocham/Documents/code/memory-system/src/main/java/com/memsys/skill/SkillService.java)、[src/main/java/com/memsys/tool/LoadSkillTool.java](/Users/leocham/Documents/code/memory-system/src/main/java/com/memsys/tool/LoadSkillTool.java)、[src/main/java/com/memsys/tool/SearchRagTool.java](/Users/leocham/Documents/code/memory-system/src/main/java/com/memsys/tool/SearchRagTool.java)、[src/test/java/com/memsys/cli/ConversationCliTest.java](/Users/leocham/Documents/code/memory-system/src/test/java/com/memsys/cli/ConversationCliTest.java)、[src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java](/Users/leocham/Documents/code/memory-system/src/test/java/com/memsys/prompt/SystemPromptBuilderTest.java) 和 [src/test/java/com/memsys/e2e/RealApiE2ETest.java](/Users/leocham/Documents/code/memory-system/src/test/java/com/memsys/e2e/RealApiE2ETest.java)。

## 1. 是否建议合并

**局部可合并，不建议整段合并。**

`5.4` 已经比较接近真实实现，但 `4.7`、`4.8`、`6.3`、`6.5` 仍然有明显的事实超前和总结过满问题，先不要直接进入正文定稿。

## 2. 主要问题分类

### 2.1 真实实现不一致

1. `4.7` 和 `4.8` 把 `memory-access / memory-core / memory-worker / memory-eval` 写成了当前已落地的多项目形态，但仓库与 `开发实现process.md` 仍是单仓库模块化实现，这些内容只能算后续路线。
2. `6.2.4` 中的 `RealApiE2ETest` 是默认关闭的真实 API 回归入口，只有在 `MEMSYS_RUN_REAL_API_E2E=true` 且 `OPENAI_API_KEY` 可用时才启用，不能写成常规自动化测试结论。
3. `6.3` 把“已有测试基础”推进成“系统已经可行且有效”，力度超过当前仓库能够直接证明的范围。
4. `6.5` 更像实验指标预案，不是已经产出的实验结果或固定统计口径。

### 2.2 AI 味与重复表达

1. `5.4` 的信息是对的，但有少量说明书式句子，像“不是每轮都要携带的固定上下文”这类表达偏整齐。
2. `6.3` 的末段收得太满，像结论模板，不像学生基于测试结果写出来的分析。
3. `6.5` 使用了“可直接落地”“统一呈现”这类很顺的表述，整体偏整理稿口吻。

### 2.3 章节衔接

1. 第 4 章后半部分已经在写未来架构，但正文没有明确降级成“后续设想”，容易把路线图写成当前成果。
2. 第 6 章缺少固定题集和可重复统计数据时，不宜先写满“有效”“可行”的结论。
3. `5.4` 和 `5.6` 之间衔接还可以更紧一点，最好继续围绕触发条件、读取对象和工具暴露条件说话。

## 3. 逐段精修建议

### 建议 1：收紧 `5.4` 的触发条件

原句偏满：

`系统并不会把所有 Skill 正文一次性注入 Prompt，而只会先输出可用 Skill 名称，再在需要时通过 load_skill(name) 按名称读取正文。`

建议改成：

`系统先暴露可用 Skill 名称，只有在反思结果允许且模型确实需要时，才通过 `load_skill(name)` 读取单个 Skill 正文。`

### 建议 2：把 `4.7`、`4.8` 明确降级为后续方案

建议统一改成：

`当前仓库仍是单仓库模块化实现，本文这里只给出后续协同拆分路线，用于说明可能的工程演进方向。`

这样可以避免把路线图写成现阶段已经完成的多项目部署。

### 建议 3：修正 `6.3` 的结论力度

原句：

`因此，本文提出的架构与实现方案在毕业设计场景下是可行且有效的。`

建议改成：

`结合当前测试结果，可以说明该方案已经达到可实现、可运行和可检查的工程目标；至于效果增益和指标提升，还需要后续固定数据集继续补证。`

### 建议 4：把 `6.5` 改成指标预案

原句：

`为支持答辩中的量化展示，本文给出可直接落地的指标口径。`

建议改成：

`为支持后续答辩展示，本文整理了可扩展的指标口径，这些指标主要服务于后续实验设计，并不表示当前版本已经完成统计。`

### 建议 5：补正 `6.2.4` 的测试边界

建议写成：

`RealApiE2ETest` 是默认关闭的真实模型接口回归入口，只有在配置了环境变量后才启用，用于检查完整链路在真实 API 条件下是否可运行。

## 4. 人味化检查结果

1. 当前稿件不是典型 AI 拼装文，但仍有“句子太整齐”的问题，尤其是第 4 章后半段和第 6 章结论段。
2. 最自然的部分是 `5.4` 到 `5.6` 这一段，能落到类名、方法名、条件判断和文件名，像作者自己读过代码后写的。
3. 最需要压低的不是内容本身，而是那种“写得太完整”的总结感。

结论：

**当前稿件整体可读，但还残留整理型 AI 味，主要问题集中在后续方案写实、测试结论收束和实验指标表述。**

## 5. 下一轮建议

1. 下一轮优先只修 `4.7`、`4.8`、`6.2.4`、`6.3`、`6.5`，不要继续扩章。
2. `4.7`、`4.8` 统一改成“后续演进方案”，不要写成当前实现。
3. `6.3` 统一降到“可运行、可检查、已有验证基础”，先不要写“有效”“显著提升”。
4. `6.5` 明确改成“后续实验指标设计”或“指标预案”，避免把规划写成成果。
5. `5.4` 保持现在的实现贴合度，只再压低一点说明书式语气即可。

本轮最终判断：

1. `5.4`：**局部精修后可合并**
2. `4.7`、`4.8`：**暂不建议直接合并**
3. `6.2.4`：**补清测试边界后可合并**
4. `6.3`：**暂不建议直接合并**
5. `6.5`：**暂不建议直接合并**
