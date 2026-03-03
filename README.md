# Memory Box - 记忆管理系统

基于 Java + Spring Boot + LangChain4j 构建的多层级记忆管理系统，参考 ChatGPT 的记忆机制实现。

## 功能特性

- 多层级系统提示词架构（6个核心区块）
- 显式与隐式记忆提取引擎
- 自动记忆管理与 Top of Mind 算法（双向队列 LRU）
- 用户控制面板与隐私保护
- 文件系统持久化存储
- 纯 CLI 交互界面

## 技术栈

- Java 17+
- Spring Boot 3.2.0
- LangChain4j 0.35.0
- OpenAI 格式 API（兼容多种模型）
- Jackson（JSON 处理）
- Lombok（简化代码）

## 快速开始

### 1. 配置环境变量

```bash
export OPENAI_API_KEY="your-api-key"
export OPENAI_BASE_URL="https://api.openai.com/v1"
export OPENAI_MODEL="gpt-4"
```

### 2. 编译项目

```bash
mvn clean package
```

### 3. 运行应用

```bash
# 正常模式
java -jar target/memory-box-1.0.0.jar

# 临时对话模式（不保存记忆）
java -jar target/memory-box-1.0.0.jar --temporary
```

## 使用说明

### CLI 命令

- `/help` - 显示帮助信息
- `/memories` - 查看所有记忆
- `/delete <槽位名>` - 删除指定记忆
- `/cleanup` - 清理长期未访问的记忆
- `/exit` - 退出系统

### 记忆类型

1. **Model Set Context** - 对话历史摘要
2. **User Insights** - 用户档案卡（职业、偏好、背景等）
3. **Notable Highlights** - 显著话题

### 存储结构

所有记忆数据存储在 `.memory/` 目录下：

- `metadata.json` - 用户元数据和助手偏好
- `model_set_context.json` - 显式记忆
- `implicit_memories.json` - 隐式记忆
- `recent_user_messages.jsonl` - 最近40条用户消息
- `conversation_history.jsonl` - 完整对话历史
- `memory_queues.json` - 双向队列状态

## 配置说明

编辑 `src/main/resources/application.yml`：

```yaml
llm:
  api-key: ${OPENAI_API_KEY}
  base-url: ${OPENAI_BASE_URL}
  model-name: ${OPENAI_MODEL}

memory:
  max-slots: 100              # 最大槽位数
  days-unaccessed: 30         # 未访问天数阈值
  top-of-mind-limit: 15       # Top of Mind 记忆数量
  recent-messages-limit: 40   # 最近消息数量
```

## 开发说明

详细的开发文档请参考 `开发文档.md`。

## 许可证

MIT License
