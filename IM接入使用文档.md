# IM 接入使用文档（飞书 + Telegram）

本文档用于快速完成两个 IM 渠道的联调：
- 接收消息后执行任务（调用 `ConversationCli.processUserMessage()`）
- 主动发送消息（`POST /im/send`）
- 用户提出提醒/日程需求时，由模型按需调用 `create_task` 工具创建任务，并在到点后主动推送提醒
- 接收消息后可由模型调用 `run_python_script` 执行 `scripts/` 下 Python 脚本

## 0. 文档基线（先读）

官方文档（主依据）：
- 飞书开放平台：https://open.feishu.cn/
- 飞书开放平台文档：https://open.feishu.cn/document/
- 服务端 SDK：https://open.feishu.cn/document/server-docs/server-side-sdk
- 机器人开发指南：https://open.feishu.cn/document/ukTMukTMukTM/uYjNwUjL2YDM14iN2ATN
- 事件订阅文档：https://open.feishu.cn/document/ukTMukTMukTM/uUTNz4SN1MjL1UzM
- 权限列表：https://open.feishu.cn/document/ukTMukTMukTM/uQjNz4CN1MjL2czN
- 发送消息 API（im.message.create）：https://open.feishu.cn/document/server-docs/im-v1/message/create
- tenant_access_token（自建应用）：https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal
- WebSocket 长连接模式（官方）：https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/event-subscription-guide/long-connection-mode

第三方文档（仅辅助）：
- OpenClaw 飞书接入教程：https://m.163.com/dy/article/KMO4FEP105566SCS.html
- 飞书 WebSocket 长连接模式（CSDN）：https://m.blog.csdn.net/u014177256/article/details/158267848

## 1. 准备 `.env`

```dotenv
# IM 开关
IM_API_ENABLED="true"
IM_FEISHU_ENABLED="true"
IM_TELEGRAM_ENABLED="false"

# Telegram
TELEGRAM_BOT_TOKEN=""
TELEGRAM_WEBHOOK_SECRET=""
TELEGRAM_WEBHOOK_URL=""

# Feishu（推荐：长连接）
FEISHU_APP_ID=""
FEISHU_APP_SECRET=""
FEISHU_LONG_CONNECTION_ENABLED="true"
FEISHU_WEBHOOK_ENABLED="false"

# Feishu webhook 模式才需要
FEISHU_VERIFICATION_TOKEN=""
FEISHU_WEBHOOK_URL=""

# Python 工具
PYTHON_EXECUTABLE="python3"
```

本地快速联调（飞书）最小配置：
- 必填：`FEISHU_APP_ID`、`FEISHU_APP_SECRET`
- 推荐：`FEISHU_LONG_CONNECTION_ENABLED=true`
- 本地私有场景下，不需要公网 `FEISHU_WEBHOOK_URL`

## 2. 启动 IM 服务

```bash
export JAVA_HOME=$(/usr/libexec/java_home)
mvn clean package

java -jar target/memory-box-1.0.0.jar \
  --cli.enabled=false
```

说明：
- `--cli.enabled=false`：关闭交互式 CLI，避免阻塞终端
- IM 开关由 `.env` 控制（`IM_FEISHU_ENABLED` / `IM_TELEGRAM_ENABLED`）

## 3. Telegram 联调步骤（Webhook）

### 3.1 获取 Token

在 Telegram 的 `@BotFather`：
- 创建机器人（`/newbot`）
- 获取 `TELEGRAM_BOT_TOKEN`

### 3.2 配置 webhook

```bash
curl -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -d "url=${TELEGRAM_WEBHOOK_URL}" \
  -d "secret_token=${TELEGRAM_WEBHOOK_SECRET}"
```

可选校验：

```bash
curl "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getWebhookInfo"
```

### 3.3 回归测试

- 在 Telegram 给机器人发一条文本消息
- 机器人应自动回复（由本项目生成）

## 4. 飞书联调步骤（长连接推荐）

### 4.1 获取凭证

在飞书开放平台创建企业自建应用并开启机器人能力后，获取：
- `FEISHU_APP_ID`
- `FEISHU_APP_SECRET`

### 4.2 飞书后台配置（长连接）

在应用后台的事件订阅中：
- 订阅方式选择：长连接模式（WebSocket）
- 订阅事件：`im.message.receive_v1`

在「权限配置」中添加以下权限：

| 权限名称 | 权限标识 | 用途 |
|---------|---------|------|
| 获取与更新用户基本信息 | `contact:user.base:readonly` | 获取用户信息 |
| 接收群聊消息 | `im:message.group:receive` | 接收群消息 |
| 接收单聊消息 | `im:message.p2p:receive` | 接收私聊消息 |
| 读取群消息 | `im:message.group_msg:readonly` | 读取群消息内容 |
| 读取单聊消息 | `im:message.p2p_msg:readonly` | 读取私聊内容 |
| 以应用身份发送群消息 | `im:message:send_as_bot` | 发送消息回复用户 |

说明：
- 长连接模式下，不需要配置 `FEISHU_WEBHOOK_URL`
- `FEISHU_VERIFICATION_TOKEN` 仅 webhook 模式需要
- 目前本项目按控制台逐项勾选权限，未依赖 JSON 批量导入导出

### 4.3 回归测试

- 启动服务后观察日志中出现 `Feishu long connection started.`
- 在飞书会话中给机器人发送文本
- 机器人应自动回复

### 4.4 如果切回 webhook 模式（可选）

当你需要 webhook 模式时再开启：
- `FEISHU_WEBHOOK_ENABLED=true`
- `FEISHU_LONG_CONNECTION_ENABLED=false`
- 填写 `FEISHU_VERIFICATION_TOKEN`
- 填写公网 `FEISHU_WEBHOOK_URL`（指向 `/webhooks/feishu/event`）

## 5. 主动发送接口（平台无关）

```bash
curl -X POST http://localhost:8080/im/send \
  -H "Content-Type: application/json" \
  -d '{
    "platform": "telegram",
    "conversationId": "123456789",
    "text": "hello from memory-box"
  }'
```

飞书示例：

```bash
curl -X POST http://localhost:8080/im/send \
  -H "Content-Type: application/json" \
  -d '{
    "platform": "feishu",
    "conversationId": "oc_xxx",
    "text": "hello from memory-box"
  }'
```

## 6. 常见问题

- 为什么不填 `FEISHU_WEBHOOK_URL` 也能跑？
  - 因为当前使用的是飞书长连接模式，SDK 主动连飞书接事件，不依赖公网回调。
- 为什么还保留 `FEISHU_VERIFICATION_TOKEN` 和 `FEISHU_WEBHOOK_URL`？
  - 为了兼容 webhook 模式；你当前私有本地场景可以留空。
- 权限能否 JSON 批量导入导出？
  - 当前接入流程中使用控制台逐项添加权限。

## 6.1 飞书“收不到消息”核对清单（长连接）

优先看启动日志中的 `FEISHU-CHECK`：
- `enabled=true`
- `longConnectionEnabled=true`
- `appSecretSet=true`

如果以上任一不满足，先修 `.env` 并重启。

飞书后台需同时满足：
- 应用已开启机器人能力，并已发布到当前租户/可用范围
- 事件订阅方式：长连接模式（WebSocket）
- 已订阅事件：`im.message.receive_v1`
- 权限已添加并审批通过，建议至少包括：
  - `im:message.p2p:receive`
  - `im:message.p2p_msg:readonly`
  - `im:message:send_as_bot`

联调时的日志判定标准：
- 看到 `Feishu long connection started.`：长连接客户端已启动
- 看到 `Feishu inbound received ... chatId=oc_xxx`：消息已进入本服务
- 看到 `Feishu outbound send success ...`：回复已发送成功
- 若只有发送失败且为 `invalid receive_id`：`conversationId` 不是有效 `chat_id`

## 7. 定时任务与 Python 脚本说明

- 定时任务自动提取：
  - 用户消息含“提醒/定时/会议 + 时间”语义时，会自动形成任务
  - 任务触发后优先通过来源 IM 会话主动推送提醒
- Python 脚本执行：
  - 模型可调用 `run_python_script(script, args_json)`
  - 脚本限制在 `scripts/` 目录，且必须是 `.py`
  - `args_json` 示例：`["--date","2026-03-20"]`
