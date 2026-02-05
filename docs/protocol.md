# 通信协议规范

## 协议概述

- **传输**: WebSocket over TLS (wss://)
- **编码**: JSON
- **方向**: 全双工

## 连接流程

```
1. 手机连接 wss://server.com/ws
2. 携带 headers:
   - X-User-ID: user_xxx
   - X-Device-Name: "我的手机"
3. 服务端验证 user_id
4. 连接建立，开始 3分钟心跳
```

## 消息定义

### 上行（手机 → 云端）

#### 1. ping - 心跳

```json
{
  "type": "ping",
  "user_id": "user_xxx",
  "timestamp": 1707112800000
}
```

**说明**: 每3分钟发送一次，保持 NAT 表项活跃

---

#### 2. ui_event - UI 事件上报

```json
{
  "type": "ui_event",
  "user_id": "user_xxx",
  "session_id": "session_xxx",
  "package": "com.example.app",
  "activity": "MainActivity",
  "screen": {
    "width": 1080,
    "height": 2400,
    "orientation": "portrait"
  },
  "elements": [
    {
      "id": 0,
      "type": "input",
      "text": "请输入手机号",
      "desc": "手机号输入框",
      "pos": [9.3, 16.7, 90.7, 21.7],
      "center": [50.0, 19.2],
      "actions": ["click", "input"]
    }
  ],
  "stats": {
    "original_nodes": 156,
    "filtered_nodes": 12
  }
}
```

**说明**: 界面变化时自动触发，防抖 300ms

---

#### 3. authorization - 授权响应

```json
{
  "type": "authorization",
  "user_id": "user_xxx",
  "session_id": "session_xxx",
  "decision": "allowed"
}
```

**decision 枚举**: `allowed` | `denied`

---

#### 4. resume - 恢复任务

```json
{
  "type": "resume",
  "user_id": "user_xxx",
  "session_id": "session_xxx"
}
```

**说明**: 用户从暂停状态点击"继续"时发送

---

### 下行（云端 → 手机）

#### 1. pong - 心跳响应

```json
{
  "type": "pong",
  "timestamp": 1707112800000,
  "server_time": 1707112800001
}
```

**说明**: 收到 ping 后 5 秒内返回

---

#### 2. request_control - 请求控制

```json
{
  "type": "request_control",
  "user_id": "user_xxx",
  "session_id": "session_xxx",
  "goal": "帮我在淘宝签到",
  "timeout_ms": 60000
}
```

**说明**: 新任务开始时发送，等待用户授权

---

#### 3. action - 执行指令

```json
{
  "type": "action",
  "action": "click",
  "target": {
    "element_id": 3,
    "center": [50.0, 35.8]
  },
  "expect": "进入登录页面"
}
```

**action 枚举**:
- `click`: 点击
- `input`: 输入文字
- `swipe`: 滑动
- `back`: 返回
- `pause`: 暂停任务
- `done`: 任务完成

**input 示例**:
```json
{
  "type": "action",
  "action": "input",
  "target": {
    "element_id": 2,
    "center": [50.0, 20.0]
  },
  "text": "13800138000"
}
```

**swipe 示例**:
```json
{
  "type": "action",
  "action": "swipe",
  "direction": "up"
}
```

**pause 示例**:
```json
{
  "type": "action",
  "action": "pause",
  "reason": "检测到支付确认，需要用户亲自操作"
}
```

---

#### 4. task_end - 任务结束

```json
{
  "type": "task_end",
  "session_id": "session_xxx",
  "status": "success",
  "result": "签到成功，获得10金币"
}
```

**status 枚举**: `success` | `failed` | `cancelled`

---

#### 5. task_metrics - 任务指标（Token消耗）

```json
{
  "type": "task_metrics",
  "session_id": "session_xxx",
  "current_step": 5,
  "total_steps": 12,
  "current_cost": 0.03,
  "total_tokens": 7500,
  "input_tokens": 6500,
  "output_tokens": 1000,
  "model": "gpt-4o-mini"
}
```

**说明**: 
- 每步AI决策后下发，实时同步消耗情况
- 悬浮窗显示 `current_cost`
- 任务结束后可累计统计

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `current_step` | number | 当前步骤数 |
| `total_steps` | number | 预估总步骤数（可选） |
| `current_cost` | number | 当前任务累计成本（美元） |
| `total_tokens` | number | 累计Token数 |
| `input_tokens` | number | Input Token数 |
| `output_tokens` | number | Output Token数 |
| `model` | string | 使用的模型 |

---

## 状态机

### 任务状态

```
┌─────────┐    request_control    ┌──────────┐
│  Idle   │ ────────────────────► │ Pending  │
│ (待命)  │                       │ (待授权) │
└─────────┘                       └────┬─────┘
     ▲                                 │
     │                                 │ authorization
     │                                 │ (allowed)
     │ task_end                        ▼
     │                            ┌──────────┐
     └────────────────────────────┤ Running  │
                                  │ (运行中) │
                                  └────┬─────┘
                                       │ action: pause
                                       ▼
                                  ┌──────────┐
                                  │ Paused   │
                                  │ (已暂停) │
                                  └────┬─────┘
                                       │ resume
                                       │ (用户点击继续)
                                       └──────────┐
                                                  │
                                                  ▼
                                           ┌──────────┐
                                           │ Running  │
                                           └──────────┘
```

## 错误处理

### 连接错误

- 网络断开: 指数退避重连 (1s → 2s → 4s → ... → 60s)
- 认证失败: 提示用户检查 user_id
- 服务端错误: 延迟 10s 后重连

### 业务错误

| 场景 | 处理 |
|------|------|
| 授权超时 (60s) | 云端取消任务 |
| 用户拒绝授权 | 云端取消任务 |
| 动作执行失败 | AI 从 UI 变化判断，重试或报错 |
| 任务超时 (30min 无活动) | 云端结束任务 |

## 版本

- **协议版本**: v1.0
- **最后更新**: 2025-02-05
