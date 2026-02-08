# GhostTap - AGENTS.md

> 本文档面向 AI 编程助手，介绍 GhostTap 项目的架构、技术栈和开发规范。

---

## 项目概述

GhostTap 是一个 **AI 驱动的 Android 远程控制系统**，让用户通过自然语言描述任务，AI 自动在手机上完成操作。

### 核心价值
- **AI 自动化** - 自然语言描述任务，AI 自动完成
- **无障碍操作** - 基于 Android AccessibilityService，无需 Root
- **隐私优先** - 敏感操作自动暂停，数据不留存
- **开源可审计** - 服务端代码开放，可自建服务器
- **低延迟** - WebSocket 实时通信，响应 <100ms

### 系统架构

```
┌─────────────────────────────────────┐
│  用户: "帮我签到"                    │
│  OpenClaw / Feishu / 任意 IM        │
└─────────────┬───────────────────────┘
              │
┌─────────────▼───────────────────────┐
│  GhostTap Server (云端)              │
│  - WebSocket Gateway                 │
│  - AI Core (LLM)                     │
│  - UI Formatter                      │
└─────────────┬───────────────────────┘
              │ WebSocket (wss://)
┌─────────────▼───────────────────────┐
│  GhostTap Android (手机端)           │
│  - Accessibility Service             │
│  - WebSocket Client                  │
│  - Floating Window                   │
└─────────────────────────────────────┘
```

---

## 技术栈

### 服务端 (apps/server)
- **语言**: TypeScript (Node.js 20+)
- **核心依赖**:
  - `ws` - WebSocket 服务器
  - `sqlite3` + `sqlite` - SQLite 数据库
  - `dotenv` - 环境变量管理
  - `uuid` - UUID 生成
- **开发工具**: `tsx` (TypeScript 执行器), `typescript`

### Android 客户端 (apps/android)
- **语言**: Java (JDK 17)
- **构建工具**: Gradle
- **minSdk**: 24 (Android 7.0)
- **targetSdk**: 34
- **核心依赖**:
  - OkHttp 4.12.0 - WebSocket 客户端
  - Gson 2.10.1 - JSON 序列化
  - AndroidX Core 1.12.0

### 协议包 (packages/protocol)
- TypeScript 共享类型定义
- 用于服务端和客户端之间的通信协议

### OpenClaw Skill (openclaw-skill)
- OpenClaw 技能插件
- 与 IM 平台（Feishu 等）集成

---

## 项目结构

```
ghosttap/
├── apps/
│   ├── android/          # Android 客户端 (Java)
│   │   ├── app/src/main/java/com/aska/ghosttap/
│   │   │   ├── MainActivity.java           # 主界面
│   │   │   ├── GhostTapService.java        # 无障碍服务核心
│   │   │   ├── WebSocketManager.java       # WebSocket 连接管理
│   │   │   ├── AccessibilityCollector.java # UI 采集器
│   │   │   ├── CommandExecutor.java        # 指令执行器
│   │   │   ├── FloatWindowManager.java     # 悬浮窗管理
│   │   │   ├── Config.java                 # 配置文件
│   │   │   └── BootReceiver.java           # 开机自启动接收器
│   │   └── build.gradle
│   │
│   └── server/           # Node.js 服务端
│       ├── src/
│       │   ├── index.ts              # 入口文件
│       │   ├── config.ts             # 配置管理
│       │   ├── websocket-gateway.ts  # WebSocket 网关
│       │   ├── ai-core.ts            # AI 决策核心
│       │   ├── session-manager.ts    # 会话管理
│       │   ├── http-api.ts           # HTTP API
│       │   ├── formatter.ts          # UI 格式化
│       │   ├── database.ts           # SQLite 数据库
│       │   ├── logger.ts             # 日志工具
│       │   └── types.ts              # 类型定义
│       └── package.json
│
├── packages/
│   └── protocol/         # 共享协议定义
│       └── src/index.ts  # 通信协议类型
│
├── openclaw-skill/       # OpenClaw 技能
│   └── src/
│       ├── index.ts      # 技能入口
│       └── tools.ts      # 工具定义
│
├── docker-compose.yml    # Docker 部署配置
├── Dockerfile            # 服务端 Docker 镜像
└── package.json          # 根项目配置 (workspace)
```

---

## 构建和运行

### 服务端

```bash
# 安装所有依赖
npm run install:all

# 开发模式（热重载）
npm run dev:server

# 构建
npm run build:server

# 生产运行
npm run start:server
```

### Android 客户端

```bash
cd apps/android

# 构建 Debug APK
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Docker 部署

```bash
# 使用 docker-compose
docker-compose up -d

# 或使用 Dockerfile 直接构建
docker build -t ghosttap-server .
docker run -p 8080:8080 -p 8081:8081 ghosttap-server
```

---

## 环境变量配置

服务端通过 `.env` 文件配置（位于 `apps/server/.env`）：

```bash
# 服务器配置
PORT=8080
HOST=0.0.0.0

# AI 配置（必需）
AI_API_KEY=your_api_key_here
AI_MODEL=kimi-coding/k2p5
AI_API_URL=https://api.moonshot.cn/v1/chat/completions

# WebSocket 配置
WS_HEARTBEAT_INTERVAL=90000      # 90秒心跳间隔
WS_HEARTBEAT_TIMEOUT=180000      # 3分钟超时

# 任务配置
MAX_STEPS=50                     # 最大步骤数
TASK_TIMEOUT=1800000             # 30分钟任务超时
PAUSE_TIMEOUT=300000             # 5分钟暂停超时

# 存储配置
DB_PATH=./data/ghosttap.db

# 日志级别
LOG_LEVEL=info
```

---

## 通信协议

### 协议版本
- 当前版本: v3.12

### 上行消息（手机 → 云端）
- `ping` - 心跳
- `ui_event` - UI 状态上报
- `pause` - 暂停请求
- `resume` - 恢复请求
- `stop` - 停止请求
- `error` - 错误上报

### 下行消息（云端 → 手机）
- `pong` - 心跳响应
- `task_start` - 任务开始
- `action` - 动作指令（click/input/swipe/back/home/launch_app/wait/pause）
- `task_end` - 任务结束
- `task_resume` - 任务恢复

### 动作类型
- `click` - 点击指定位置
- `input` - 输入文本
- `swipe` - 滑动屏幕
- `back` - 返回上一级
- `home` - 回到桌面
- `launch_app` - 启动应用
- `wait` - 等待
- `pause` - 暂停（需要用户确认）
- `done` - 任务完成
- `fail` - 任务失败

---

## 代码规范

### TypeScript (服务端)
- 使用 `strict: true` 严格模式
- 所有 public 方法必须标注返回类型
- 使用 async/await 处理异步操作
- 错误处理使用 try-catch，并通过 logger 记录

### Java (Android)
- 遵循 Java 17 语法
- 所有类成员使用显式访问修饰符
- 资源释放使用 try-finally 或 try-with-resources
- AccessibilityNodeInfo 必须正确 recycle 避免内存泄漏

### 注释规范
- 类和方法使用 Javadoc/TSDoc 格式
- 复杂逻辑添加行内注释
- 关键版本更新添加 `// v3.12:` 标记

---

## 安全设计

### 两层敏感检测
1. **第一层（硬检测）** - `formatter.ts` 中的 `SENSITIVE_KEYWORDS`
   - 零成本、零延迟
   - 精确匹配关键词（支付、密码等）
   - 在调用 AI 之前执行

2. **第二层（AI 软检测）** - AI 决策时检测
   - 检测变体表述和上下文
   - 覆盖第一层无法捕获的场景

### 其他安全措施
- **单设备绑定** - 同一 user_id 只能有一个连接
- **TLS 加密** - 所有通信使用 wss://
- **数据不留存** - UI 数据不存储，仅用于实时决策
- **百分比坐标** - 使用百分比而非绝对像素，适配不同屏幕

---

## 核心模块说明

### WebSocket Gateway
- 管理所有 WebSocket 连接
- 处理心跳检测（90秒间隔，3分钟超时）
- 消息路由和分发
- 断连恢复处理

### AI Core
- 调用 LLM 进行决策
- 构建 System Prompt 和 User Prompt
- 重试机制（指数退避 + 抖动）
- Token 成本计算

### Session Manager
- 管理任务会话生命周期
- SQLite 持久化存储
- 定期清理过期会话
- 用户活跃会话追踪

### Accessibility Collector (Android)
- 采集 UI 树信息
- 预过滤可交互元素（最多50个）
- 百分比坐标转换
- 软键盘检测

### Command Executor (Android)
- 执行云端下发的指令
- 手势操作（点击、滑动、输入）
- 系统操作（返回、Home、启动应用）
- 节点回收管理

---

## 调试和日志

### 服务端日志
```typescript
import { logger } from './logger';

logger.debug('调试信息');
logger.info('一般信息');
logger.warn('警告信息');
logger.error('错误信息', error);
```

### 日志级别
- `debug` - 详细调试信息
- `info` - 一般运行信息（默认）
- `warn` - 警告
- `error` - 错误

### Android 日志
```java
Log.d(TAG, "调试信息");
Log.i(TAG, "一般信息");
Log.w(TAG, "警告信息");
Log.e(TAG, "错误信息", throwable);
```

---

## API 端点

HTTP API 运行在 WebSocket 端口 + 1（默认 8081）：

- `GET /health` - 健康检查
- `POST /api/tasks` - 创建任务
- `GET /api/tasks/:sessionId` - 获取任务状态
- `GET /api/tasks/:sessionId/history` - 获取任务历史
- `GET /api/stats` - 服务器统计

---

## 已知限制

1. **WebView 页面** - 无法采集 WebView 内部内容，会快速失败
2. **系统弹窗** - 权限弹窗等系统覆盖层需要特殊处理
3. **软键盘** - 需要检测键盘状态并调整点击位置
4. **来电覆盖** - 来电时会自动暂停

---

## 贡献指南

1. 遵循现有代码风格
2. 新功能添加对应的注释和文档
3. 重大变更更新版本标记（如 `// v3.13:`）
4. 确保 Android 端资源正确回收
5. 服务端变更考虑向后兼容性

---

## 许可证

MIT License
