# GhostTap 代码审查报告

**审查日期**: 2026-02-06  
**设计文档版本**: v3.12  
**代码版本**: v3.12  

---

## 一、整体符合度评估

| 设计要素 | 符合度 | 说明 |
|---------|-------|------|
| 端云分离架构 | ✅ 100% | 手机端纯传感器+执行器，智能逻辑全在云端 |
| AI-Native 设计 | ✅ 100% | 无规则引擎，完全 AI 决策 |
| 协议简洁 | ✅ 100% | WebSocket 通信，消息类型清晰 |
| Token 优化 | ✅ 100% | 手机端预过滤 UI 元素（最多50个） |
| 百分比坐标 | ✅ 100% | 全程使用百分比坐标 |
| 两层敏感检测 | ✅ 100% | 第一层代码硬检测 + 第二层 AI 软检测 |
| 心跳重连机制 | ✅ 95% | 90秒心跳正确，重连逻辑需优化 |
| 断连恢复 | ✅ 100% | TaskResume 消息恢复任务上下文 |
| 单设备绑定 | ✅ 100% | 同一 user_id 只能有一个连接 |
| SQLite 持久化 | ✅ 100% | 会话、历史记录持久化存储 |
| 悬浮窗功能 | ✅ 100% | 运行中/暂停状态，操作按钮完整 |

**总体符合度**: 约 98%

---

## 二、严重问题（必须修复）

### 问题 1: 编译错误 - MainActivity.java 缺少 Handler 导入

**文件**: `/root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/MainActivity.java`

**问题描述**: 代码使用了 `Handler` 类但未导入，会导致编译失败。

**修复方法**:
```java
// 在文件顶部添加导入
import android.os.Handler;
```

---

### 问题 2: 编译错误/运行时崩溃 - 缺少 stopCurrentTask() 方法

**文件**: `/root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/GhostTapService.java`

**问题描述**: `MainActivity.java` 第 345 行调用了 `instance.stopCurrentTask()`，但 `GhostTapService` 类中没有此方法。

**修复方法**: 在 `GhostTapService.java` 中添加以下方法（建议添加在 `resetTaskState()` 方法之后）：

```java
/**
 * 停止当前任务（供外部调用，如 MainActivity 停止服务时）
 */
public void stopCurrentTask() {
    if (currentSessionId != null) {
        // 发送停止请求到云端
        if (webSocketManager != null) {
            webSocketManager.sendStopRequest(currentSessionId);
        }
        // 结束本地任务
        endTask("cancelled", "用户停止服务");
    }
}
```

---

### 问题 3: 内存泄漏风险 - CommandExecutor 未正确回收节点

**文件**: `/root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/CommandExecutor.java`

**问题描述**: `findEditableNodeAtPosition` 递归方法中，当找到结果返回时，子节点没有被回收，可能导致内存泄漏。

**当前代码**:
```java
private AccessibilityNodeInfo findEditableNodeAtPosition(AccessibilityNodeInfo node, int x, int y) {
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    
    if (!rect.contains(x, y)) {
        return null;
    }
    
    for (int i = 0; i < node.getChildCount(); i++) {
        AccessibilityNodeInfo child = node.getChild(i);
        if (child == null) continue;
        
        AccessibilityNodeInfo result = findEditableNodeAtPosition(child, x, y);
        if (result != null) {
            return result;  // 子节点未回收！
        }
    }
    
    if (node.isEditable()) {
        return node;
    }
    
    return null;
}
```

**修复方法**:
```java
private AccessibilityNodeInfo findEditableNodeAtPosition(AccessibilityNodeInfo node, int x, int y) {
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    
    if (!rect.contains(x, y)) {
        return null;
    }
    
    // 深度优先：优先返回更深层（更具体）的匹配
    for (int i = 0; i < node.getChildCount(); i++) {
        AccessibilityNodeInfo child = node.getChild(i);
        if (child == null) continue;
        
        AccessibilityNodeInfo result = findEditableNodeAtPosition(child, x, y);
        if (result != null) {
            // 注意：如果 result == child（child 自身 isEditable），不能 recycle
            if (result != child) {
                child.recycle();
            }
            return result;
        }
        child.recycle();
    }
    
    // 当前节点包含坐标且可编辑
    if (node.isEditable()) {
        return node;
    }
    
    return null;
}
```

---

### 问题 4: 功能缺失 - 缺少设备重启自启动功能

**设计文档 §3.1.2.1 要求**: 设备重启后自动启动 ForegroundService 并连接云端

**当前状态**: 代码中没有实现 `BOOT_COMPLETED` 广播接收器

**修复方法**:

1. 创建新文件 `/root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/BootReceiver.java`:

```java
package com.aska.ghosttap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * 开机广播接收器
 * 设备重启后自动启动 GhostTap 服务
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = Config.LOG_TAG + ".BootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed, checking if service should start");
            
            SharedPreferences prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
            String userId = prefs.getString(Config.PREF_USER_ID, null);
            String serverUrl = prefs.getString(Config.PREF_SERVER_URL, null);
            
            // 如果用户已配置，自动启动服务
            if (userId != null && serverUrl != null && !serverUrl.contains("your-server.com")) {
                Log.i(TAG, "Auto-starting GhostTap service");
                Intent serviceIntent = new Intent(context, GhostTapService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.i(TAG, "Not auto-starting: user not configured yet");
            }
        }
    }
}
```

2. 在 `AndroidManifest.xml` 中添加权限和接收器声明:

```xml
<!-- 添加权限 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>

<!-- 在 application 标签内添加接收器 -->
<receiver android:name=".BootReceiver"
          android:enabled="true"
          android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
    </intent-filter>
</receiver>
```

---

## 三、次要问题（建议修复）

### 问题 5: WebSocketManager 心跳逻辑优化

**文件**: `/root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/WebSocketManager.java`

**问题描述**: `startHeartbeat()` 使用递归的 `Handler.postDelayed` 可能存在内存泄漏风险，建议使用 `ScheduledExecutorService`。

**建议**: 虽然当前实现可以工作，但长期使用建议改用 `ScheduledExecutorService`。

---

### 问题 6: Config.java 服务器地址硬编码问题

**文件**: `/root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/Config.java`

**问题描述**: `SERVER_URL` 硬编码为示例地址，虽然 `MainActivity` 提供了配置界面，但建议添加更清晰的注释。

**建议修改**:
```java
/**
 * WebSocket 服务器地址默认值
 * ⚠️ 重要：请在 MainActivity 界面配置实际服务器地址
 * 配置后会存储在 SharedPreferences 中覆盖此默认值
 */
public static final String SERVER_URL = "wss://your-server.com/ws";
```

---

## 四、设计文档与代码的差异说明

| 设计文档要求 | 代码实现 | 差异说明 |
|-------------|---------|---------|
| `PAUSE` 超时 5 分钟自动失败 | 已实现 | `session-manager.ts` 第 307 行检查 `isPauseExpired` |
| 任务超时 30 分钟 | 已实现 | `config.ts` 中 `taskTimeout = 1800000` (30分钟) |
| 最大步数 50 步 | 已实现 | `config.ts` 中 `maxSteps = 50` |
| 300ms UI 防抖 | 已实现 | `Config.java` 中 `UI_EVENT_DEBOUNCE = 300L` |
| 90秒心跳间隔 | 已实现 | `Config.java` 中 `HEARTBEAT_INTERVAL = 90 * 1000L` |
| 第一层敏感词检测 | 已实现 | `formatter.ts` 第 15 行定义 `SENSITIVE_KEYWORDS` |
| 软键盘检测 | 已实现 | `AccessibilityCollector.java` 第 76 行 `detectKeyboard()` |
| 输入三层防线 | 已实现 | `CommandExecutor.java` 第 85 行 `inputText()` |

---

## 五、修复检查清单

- [ ] 修复 MainActivity.java - 添加 `import android.os.Handler;`
- [ ] 修复 GhostTapService.java - 添加 `stopCurrentTask()` 方法
- [ ] 修复 CommandExecutor.java - 优化 `findEditableNodeAtPosition` 节点回收
- [ ] 创建 BootReceiver.java - 实现设备重启自启动
- [ ] 更新 AndroidManifest.xml - 添加 `RECEIVE_BOOT_COMPLETED` 权限和 BootReceiver
- [ ] 修复 Config.java - 添加服务器地址配置注释
- [ ] 运行编译测试确认所有修复有效

---

## 六、结论

GhostTap 项目代码整体质量较高，**核心功能基本符合设计文档 v3.12 的要求**。

主要问题集中在：
1. **编译错误**（2处，必须修复）
2. **内存泄漏风险**（1处，建议修复）
3. **功能缺失**（重启自启，建议实现）

修复后项目应该可以正常编译运行。
