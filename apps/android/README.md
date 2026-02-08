# GhostTap Android Client

GhostTap Android 客户端 - AI 驱动的远程控制。

## 📁 项目结构

```
android/
├── app/
│   ├── src/main/java/com/aska/ghosttap/
│   │   ├── MainActivity.java          # 主界面 (v3.14)
│   │   ├── GhostTapService.java       # 无障碍服务核心 (v3.14)
│   │   ├── WebSocketManager.java      # WebSocket 连接管理 (v3.12)
│   │   ├── AccessibilityCollector.java # UI 采集器 (v3.12)
│   │   ├── CommandExecutor.java       # 指令执行器 (v3.12)
│   │   ├── FloatWindowManager.java    # 悬浮窗管理 (v3.12)
│   │   ├── MessageModels.java         # 通信协议定义 (v3.12)
│   │   ├── JsonUtils.java             # JSON 工具类
│   │   └── Config.java                # 配置文件 (v3.13)
│   ├── src/main/res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml      # 主界面布局
│   │   │   ├── float_status_bar.xml   # 状态栏悬浮窗
│   │   │   ├── dialog_auth.xml        # 授权弹窗
│   │   │   └── dialog_pause.xml       # 暂停弹窗
│   │   ├── xml/
│   │   │   └── accessibility_service_config.xml  # 无障碍配置
│   │   └── values/
│   │       ├── strings.xml
│   │       └── themes.xml
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

编辑 `app/src/main/java/com/aska/ghosttap/Config.java`：

```java
public class Config {
    // WebSocket 服务器地址
    public static final String SERVER_URL = "wss://your-server.com/ws";
    
    // 其他配置...
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

| 模块 | 职责 | 版本 |
|------|------|------|
| **MainActivity** | 主界面，配置管理，权限检查 | v3.14 |
| **GhostTapService** | 无障碍服务，监听界面变化，协调各模块 | v3.14 |
| **WebSocketManager** | 管理 WebSocket 连接、心跳(90s)、重连 | v3.12 |
| **AccessibilityCollector** | 采集 UI 树，预过滤元素，百分比坐标转换，软键盘检测 | v3.12 |
| **CommandExecutor** | 执行云端指令（点击、输入、滑动、启动APP等） | v3.12 |
| **FloatWindowManager** | 显示状态栏、授权弹窗、暂停控制 | v3.12 |
| **MessageModels** | 定义客户端-服务端通信协议 | v3.12 |
| **Config** | 集中管理配置项 | v3.13 |

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
ActionCommand 下发
    │
    ▼
CommandExecutor 执行
```

## 📡 通信协议

### 上行消息（手机 → 云端）

| 消息类型 | 说明 | 版本 |
|----------|------|------|
| `ui_event` | UI 变化事件上报 | - |
| `ping` | 心跳保活（90秒间隔） | v3.12 |
| `pause` | 用户暂停任务 | v3.12 |
| `resume` | 用户恢复任务 | v3.12 |
| `stop` | 用户停止任务 | v3.12 |
| `error` | 动作执行失败上报 | v3.12 |

### 下行消息（云端 → 手机）

| 消息类型 | 说明 | 版本 |
|----------|------|------|
| `pong` | 心跳响应 | v3.12 |
| `task_start` | 任务开始（直接开始，无需授权） | v3.12 |
| `task_resume` | 断连重连后恢复任务 | v3.12 |
| `action` | 动作指令（点击、输入、滑动等） | v3.12 |
| `task_end` | 任务结束 | v3.12 |

### 支持的动作指令

| 动作 | 说明 | 参数 |
|------|------|------|
| `click` | 点击指定坐标 | `target.center` [x%, y%] |
| `input` | 输入文本（三层防线） | `target.center`, `text` |
| `swipe` | 滑动操作 | `direction`, `distance`, `duration_ms` |
| `back` | 返回键 | - |
| `home` | Home键 | - |
| `launch_app` | 启动应用 | `package_name` |
| `wait` | 等待 | `wait_ms` |
| `pause` | 暂停任务 | `reason` |

## ⚙️ 配置说明

### Config.java 主要配置项

```java
public class Config {
    // 服务端配置
    public static final String SERVER_URL = "wss://your-server.com/ws";
    
    // 心跳间隔（毫秒）- v3.12: 90秒
    public static final long HEARTBEAT_INTERVAL = 90 * 1000L;
    
    // UI 事件防抖时间（毫秒）- v3.12: 300ms
    public static final long UI_EVENT_DEBOUNCE = 300L;
    
    // 最大 UI 元素数量 - v3.12: 50个
    public static final int MAX_UI_ELEMENTS = 50;
    
    // 调试模式
    public static boolean DEBUG_MODE = false;
}
```

## � 技术栈

- **语言**: Java
- **最低 SDK**: 24 (Android 7.0)
- **目标 SDK**: 34 (Android 14)
- **Java 版本**: 17
- **主要依赖**:
  - OkHttp 4.12.0 - WebSocket 通信
  - Gson 2.10.1 - JSON 序列化
  - AndroidX Core 1.12.0

## � 权限说明

```xml
<!-- 网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 悬浮窗权限 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

## 🔄 版本历史

### v3.14 (当前)
- 优化主界面设计，清爽风格
- 添加通知栏停止按钮
- 完善状态显示

### v3.13
- 支持设备名称持久化
- 优化配置管理

### v3.12
- 移除授权流程，任务直接开始
- 新增软键盘检测
- 90秒心跳间隔
- 新增 pause/resume/stop 用户控制
- 三层防线输入策略
- 支持 launch_app 和 wait 动作

## 🐛 调试

开启详细日志：
```java
Config.DEBUG_MODE = true;
```

查看日志：
```bash
adb logcat -s GhostTap:D
```

## 📄 许可证

MIT License
