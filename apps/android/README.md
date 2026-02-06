# GhostTap Android Client

GhostTap Android 客户端 - AI 驱动的远程控制。

## 📁 项目结构

```
android/
├── app/
│   ├── src/main/java/com/ghosttap/
│   │   ├── MainActivity.kt           # 主界面
│   │   ├── GhostTapService.kt        # 无障碍服务核心
│   │   ├── WebSocketManager.kt       # WebSocket 连接管理
│   │   ├── AccessibilityCollector.kt # UI 采集器
│   │   ├── CommandExecutor.kt        # 指令执行器
│   │   ├── FloatWindowManager.kt     # 悬浮窗管理
│   │   └── Config.kt                 # 配置文件
│   ├── src/main/res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml     # 主界面布局
│   │   │   ├── float_status_bar.xml  # 状态栏悬浮窗
│   │   │   ├── dialog_auth.xml       # 授权弹窗
│   │   │   └── dialog_pause.xml      # 暂停弹窗
│   │   ├── xml/
│   │   │   └── accessibility_service_config.xml  # 无障碍配置
│   │   └── values/
│   │       └── strings.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
└── gradle/
    └── wrapper/
        └── gradle-wrapper.properties
```

## 🚀 快速开始

### 1. 配置服务端地址

编辑 `app/src/main/java/com/ghosttap/Config.kt`：

```kotlin
object Config {
    const val SERVER_URL = "wss://your-server.com/ws"
    const val API_BASE_URL = "https://your-server.com"
    ...
}
```

### 2. 构建 APK

```bash
cd apps/android
./gradlew assembleDebug
```

### 3. 安装 APK

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 📝 使用方法

1. **打开 GhostTap App**
2. **获取 User ID**：在 OpenClaw/Feishu 输入 `/get_user_id`
3. **输入 User ID** 到 App
4. **授予权限**：
   - 无障碍权限（用于采集和执行操作）
   - 悬浮窗权限（用于显示任务状态）
5. **点击"启动服务"**

## 🏗️ 架构说明

### 核心模块

| 模块 | 职责 |
|------|------|
| **GhostTapService** | 无障碍服务，监听界面变化，协调各模块 |
| **WebSocketManager** | 管理 WebSocket 连接、心跳、重连 |
| **AccessibilityCollector** | 采集 UI 树，预过滤元素，百分比坐标转换 |
| **CommandExecutor** | 执行云端指令（点击、输入、滑动等） |
| **FloatWindowManager** | 显示状态栏、授权弹窗、暂停控制 |

### 数据流

```
界面变化
    │
    ▼
AccessibilityCollector 采集 UI
    │
    ▼
预过滤（只保留可交互元素）
    │
    ▼
百分比坐标转换
    │
    ▼
WebSocketManager 上报云端
    │
    ▼
云端 AI 决策
    │
    ▼
下发 action 指令
    │
    ▼
CommandExecutor 执行
```

## ⚙️ 配置说明

### Config.kt 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `SERVER_URL` | - | WebSocket 服务器地址 |
| `HEARTBEAT_INTERVAL` | 3分钟 | 心跳间隔 |
| `UI_EVENT_DEBOUNCE` | 300ms | UI 上报防抖时间 |
| `MAX_UI_ELEMENTS` | 50 | 最大 UI 元素数量 |
| `AUTH_TIMEOUT` | 60秒 | 授权超时时间 |

## 🔒 安全设计

- **敏感操作自动暂停**：检测到支付、密码等关键词时自动暂停
- **用户授权**：每次任务都需要用户在手机上确认
- **单设备绑定**：同一 user_id 只能有一个设备连接
- **TLS 加密**：所有通信使用 wss://

## 📋 依赖

- **Kotlin**: 1.9.0
- **OkHttp**: 4.12.0 (WebSocket 客户端)
- **Kotlinx Serialization**: 1.6.0 (JSON 序列化)
- **minSdk**: 26 (Android 8.0)

## 📄 协议文档

详见 [docs/protocol.md](../../docs/protocol.md) 和 [docs/android-client-design.md](../../docs/android-client-design.md)

## 🤝 贡献

欢迎 PR 和 Issue！

## 📄 许可

MIT License
