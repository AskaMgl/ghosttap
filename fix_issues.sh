#!/bin/bash
# GhostTap 代码修复脚本
# 修复从设计文档审查中发现的问题

echo "=== GhostTap 代码修复 ==="
echo ""

# 修复 1: 添加 MainActivity.java 的 Handler 导入
echo "[1/5] 修复 MainActivity.java - 添加 Handler 导入..."
sed -i 's/import android.view.accessibility.AccessibilityManager;/import android.view.accessibility.AccessibilityManager;\nimport android.os.Handler;/' \
    /root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/MainActivity.java

# 修复 2: 在 GhostTapService.java 中添加 stopCurrentTask 方法
echo "[2/5] 修复 GhostTapService.java - 添加 stopCurrentTask 方法..."
cat >> /root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/GhostTapService.java.patch << 'EOF'
    /**
     * 停止当前任务（供外部调用）
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
EOF

# 注意：需要将上述方法插入到类中合适的位置
# 这里仅作为示例，实际需要手动编辑文件

echo "[3/5] 修复 CommandExecutor.java - 优化 findEditableNodeAtPosition 节点回收..."
# 这个修复需要手动进行，因为涉及方法体内部逻辑修改

echo "[4/5] 创建 BootReceiver.java（重启自启功能）..."
mkdir -p /root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap
cat > /root/ghosttap/apps/android/app/src/main/java/com/aska/ghosttap/BootReceiver.java << 'EOF'
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
EOF

echo "[5/5] 修复 Config.java - 添加 BOOT_COMPLETED 权限说明..."
# 这个需要在 AndroidManifest.xml 中添加，此处仅作提示

echo ""
echo "=== 修复完成 ==="
echo ""
echo "注意：以下修复需要手动完成："
echo "1. 在 GhostTapService.java 中添加 stopCurrentTask() 方法"
echo "2. 在 CommandExecutor.java 中修复 findEditableNodeAtPosition 的节点回收逻辑"
echo "3. 在 AndroidManifest.xml 中添加 BootReceiver 和 RECEIVE_BOOT_COMPLETED 权限"
echo ""
echo "AndroidManifest.xml 需要添加："
echo '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>'
echo '<receiver android:name=".BootReceiver" android:enabled="true" android:exported="true">'
echo '    <intent-filter>'
echo '        <action android:name="android.intent.action.BOOT_COMPLETED"/>'
echo '    </intent-filter>'
echo '</receiver>'
