---
name: ghosttap
description: |
  GhostTap - AI驱动的Android远程控制助手。通过无障碍服务控制用户手机完成自动化任务。
  
  Use when:
  1. 用户需要自动化操作Android手机（如：签到、购物、填写表单等）
  2. 用户说"帮我..."且涉及手机APP操作
  3. 用户需要远程控制手机执行任务
  
  支持的任务类型：
  - 自动签到（淘宝、京东、支付宝等）
  - 购物下单
  - 发送消息（微信、QQ等）
  - 任何需要手动点击的手机操作
  
  使用方法：
  1. 用户发送任务描述（如"帮我签到"）
  2. 调用 ghosttap_create_task 工具创建任务
  3. 用户会收到各种状态通知（授权请求、设备连接、任务完成等）
  4. 用户在手机上确认后，AI开始自动执行
  5. 任务完成后通过回调发送结果给用户
---

# GhostTap Skill

GhostTap 是一个 AI 驱动的 Android 远程控制助手，让用户通过自然语言描述任务，AI 自动在手机上完成操作。

## 工作流程

### 1. 用户发起任务

当用户说类似以下的话时，使用此 Skill：
- "帮我签到"
- "帮我买一杯咖啡"
- "帮我回复微信消息"
- "帮我打开XX APP做XX"

### 2. 创建任务

调用 `ghosttap_create_task` 工具，传入 `replyFn` 回调函数：

```javascript
{
  "user_id": "从消息中获取的用户ID",
  "goal": "用户原始请求（如'帮我在淘宝签到'）",
  "replyFn": reply  // OpenClaw 提供的回复函数
}
```

**重要**: 必须传入 `replyFn`，所有状态通知都通过此回调发送。

### 3. 处理响应

- **成功**：告知用户已发送授权请求，请在手机上确认
- **设备未连接**：提示用户先连接手机
- **失败**：告知用户错误原因

### 4. 状态通知

GhostTap 会在以下场景通过回调发送通知：

| 通知类型 | 内容示例 | 说明 |
|---------|---------|------|
| `auth_request` | 🤖 新的自动化任务...请在X秒内确认授权 | 需要用户手机上确认 |
| `auth_result` | ✅ 已获授权，开始执行任务 | 用户已授权 |
| `device_connected` | 📱 设备已连接 | 手机连接到服务 |
| `device_disconnected` | 📱 设备已断开 | 手机断开连接 |
| `task_completed` | ✅ 任务完成... | 任务结束（成功/失败/取消）|

### 5. 任务完成通知示例

```
✅ 任务完成

🎯 目标: 帮我在淘宝签到
📊 结果: 签到成功，获得10金币
📝 步骤: 8
💰 消耗: $0.0523
```

无需额外操作，回调会自动触发。

## 统一消息机制

GhostTap 采用统一的 OpenClaw Skill 回调机制：

```
用户 → OpenClaw → Skill → GhostTap API
                ↑
                └── GhostTap 回调 Skill → OpenClaw → 用户
```

**优势**：
- 开始和结束消息机制一致
- 支持多种状态通知
- 无需额外配置 Feishu Webhook
- 自动适配不同渠道（Feishu/Discord/Slack等）

## 注意事项

- 每次任务都需要用户在手机上确认授权
- 涉及支付、密码等敏感操作时会自动暂停，需要用户手动完成
- 任务超时时间：30分钟
- 授权超时时间：60秒
- **必须传入 `replyFn` 回调函数**，否则无法接收通知

## 用户ID获取

从 OpenClaw 消息的 author 信息中获取：
- Feishu: `author.id` 或从 session key 中提取

## 工具调用示例

```javascript
// 用户说："帮我签到"
const result = await ghosttap_create_task({
  user_id: message.author.id,  // 从消息中获取
  goal: "帮我在淘宝签到",
  replyFn: reply  // OpenClaw 提供的回复函数
});

// 返回给用户（立即响应）
if (result.success) {
  reply("已为您创建任务，请在手机上确认授权");
} else if (result.device_not_connected) {
  reply("您的手机尚未连接，请先打开 GhostTap APP 并连接");
}

// 稍后各种状态通知会自动通过 replyFn 发送给用户
```

## 回调机制说明

Skill 启动时会创建一个本地 HTTP 回调服务器（默认端口 18081），并告知 GhostTap Service。

状态变更时：
1. GhostTap Service POST 回调到 Skill 的 HTTP 服务器
2. Skill 根据消息类型构建通知文本
3. Skill 通过保存的 `replyFn` 发送消息给用户

**无需手动轮询**，回调自动触发。

## 错误处理

| 错误场景 | 回复话术 |
|---------|---------|
| 设备未连接 | "您的手机尚未连接到 GhostTap 服务，请先打开 APP 并连接" |
| 授权超时 | "授权超时，请重新发起任务" |
| 任务冲突 | "您有正在进行的任务，请先等待完成或取消" |
| 服务不可用 | "GhostTap 服务暂时不可用，请稍后重试" |
