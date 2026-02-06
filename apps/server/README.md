# GhostTap Server

GhostTap 服务端 - AI驱动的Android远程控制。

## 架构

```
┌─────────────────────────────────────┐
│         GhostTap Server              │
│  ┌─────────────────────────────┐    │
│  │    WebSocket Gateway        │    │
│  │    - 连接管理               │    │
│  │    - 心跳保活               │    │
│  │    - 消息路由               │    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │      AI Core                │    │
│  │    - LLM决策                │    │
│  │    - 敏感操作检测           │    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │    Session Manager          │    │
│  │    - 内存状态管理           │    │
│  │    - 任务生命周期           │    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │     UI Formatter            │    │
│  │    - JSON转文本             │    │
│  │    - 敏感检测               │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
```

## 快速开始

### 1. 安装依赖

```bash
cd apps/server
npm install
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，设置 AI_API_KEY
```

### 3. 开发模式

```bash
npm run dev
```

### 4. 生产模式

```bash
npm run build
npm start
```

### 5. Docker 部署

```bash
docker-compose up -d
```

## API 接口

### WebSocket 连接

```
ws://localhost:8080
```

**消息类型**：

- `ping` - 心跳
- `ui_event` - UI事件上报
- `authorization` - 授权响应
- `resume` - 恢复任务
- `action` - 动作指令
- `request_control` - 控制请求
- `task_end` - 任务结束

### HTTP API

```
http://localhost:8081
```

**端点**：

- `GET /health` - 健康检查
- `POST /api/tasks` - 创建任务
- `GET /api/tasks/:sessionId` - 获取任务状态
- `GET /api/tasks/:sessionId/history` - 获取任务历史
- `GET /api/stats` - 获取服务器统计

### 创建任务示例

```bash
curl -X POST http://localhost:8081/api/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "user_xxx",
    "goal": "帮我在淘宝签到"
  }'
```

## 配置项

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| PORT | 8080 | WebSocket端口 |
| HOST | 0.0.0.0 | 监听地址 |
| AI_MODEL | kimi-coding/k2p5 | AI模型 |
| AI_API_KEY | - | API密钥（必需） |
| AI_API_URL | - | API地址 |
| LOG_LEVEL | info | 日志级别 |

## 技术栈

- **Runtime**: Node.js 20+
- **Language**: TypeScript
- **WebSocket**: ws
- **AI Model**: kimi-coding/k2p5

## 项目结构

```
server/
├── src/
│   ├── index.ts              # 入口
│   ├── config.ts             # 配置
│   ├── logger.ts             # 日志
│   ├── types.ts              # 类型定义
│   ├── session-manager.ts    # 会话管理
│   ├── websocket-gateway.ts  # WebSocket网关
│   ├── ai-core.ts            # AI核心
│   ├── formatter.ts          # UI格式化
│   └── http-api.ts           # HTTP API
├── .env.example
├── package.json
└── tsconfig.json
```

## License

MIT