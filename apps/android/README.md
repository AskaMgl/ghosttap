# GhostTap Android Client

## 项目结构

```
android/
├── app/
│   ├── src/main/java/com/ghosttap/
│   │   ├── MainActivity.kt
│   │   ├── GhostTapService.kt
│   │   ├── WebSocketManager.kt
│   │   ├── AccessibilityCollector.kt
│   │   ├── CommandExecutor.kt
│   │   ├── FloatWindowManager.kt
│   │   └── Config.kt
│   └── src/main/res/
├── build.gradle
└── README.md
```

## 核心组件

### GhostTapService
无障碍服务，监听 UI 变化并上报。

### WebSocketManager
WebSocket 连接管理，心跳保活。

### FloatWindowManager
悬浮窗管理，显示任务状态。

## 构建

```bash
./gradlew assembleDebug
```

## 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 配置

修改 `Config.kt`:

```kotlin
object Config {
    const val SERVER_URL = "wss://your-server.com/ws"
    const val HEARTBEAT_INTERVAL = 3 * 60 * 1000L // 3分钟
}
```
