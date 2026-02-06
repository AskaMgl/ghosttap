package com.aska.ghosttap;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * 悬浮窗管理器
 * 
 * 职责：
 * 1. 显示任务状态悬浮窗
 * 2. 显示授权弹窗
 * 3. 显示暂停/恢复界面
 * 4. 显示任务完成/失败提示
 */
public class FloatWindowManager {
    
    public static final int STATUS_IDLE = 0;
    public static final int STATUS_CONNECTED = 1;
    public static final int STATUS_RUNNING = 2;
    public static final int STATUS_PAUSED = 3;
    public static final int STATUS_DISCONNECTED = 4;
    
    private static final String TAG = Config.LOG_TAG + ".FloatWindow";
    
    private final Context context;
    private final WindowManager windowManager;
    private final Handler handler;
    
    // 悬浮窗视图
    private View statusBarView;
    private AlertDialog authDialog;
    private AlertDialog pauseDialog;
    
    // 当前状态
    private int currentStatus = STATUS_IDLE;
    
    public interface AuthCallback {
        void onAllowed();
        void onDenied();
    }
    
    public FloatWindowManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 显示状态栏悬浮窗
     */
    public void showStatusBar() {
        if (statusBarView != null) return;
        if (!canDrawOverlays()) {
            Log.w(TAG, "Cannot draw overlays");
            return;
        }
        
        // 简单创建一个 TextView 作为状态栏
        statusBarView = new TextView(context);
        ((TextView) statusBarView).setText("GhostTap: 就绪");
        ((TextView) statusBarView).setBackgroundColor(0xFF2196F3);
        ((TextView) statusBarView).setTextColor(0xFFFFFFFF);
        ((TextView) statusBarView).setPadding(20, 10, 20, 10);
        
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
            WindowManager.LayoutParams.TYPE_PHONE;
        
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(Config.FLOAT_WINDOW_HEIGHT),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP;
        
        try {
            windowManager.addView(statusBarView, params);
            updateStatus("就绪", STATUS_IDLE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show status bar", e);
            statusBarView = null;
        }
    }
    
    /**
     * 隐藏状态栏悬浮窗
     */
    public void hideStatusBar() {
        if (statusBarView != null) {
            try {
                windowManager.removeView(statusBarView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove status bar", e);
            }
            statusBarView = null;
        }
    }
    
    /**
     * 更新状态栏显示
     */
    public void updateStatus(String statusText, int status) {
        currentStatus = status;
        
        if (statusBarView != null && statusBarView instanceof TextView) {
            handler.post(() -> {
                TextView tv = (TextView) statusBarView;
                tv.setText("GhostTap: " + statusText);
                
                // 更新背景颜色
                int color;
                switch (status) {
                    case STATUS_RUNNING:
                        color = 0xFF4CAF50; // 绿色
                        break;
                    case STATUS_PAUSED:
                        color = 0xFFFFC107; // 黄色
                        break;
                    case STATUS_CONNECTED:
                        color = 0xFF2196F3; // 蓝色
                        break;
                    case STATUS_DISCONNECTED:
                        color = 0xFFF44336; // 红色
                        break;
                    default:
                        color = 0xFF9E9E9E; // 灰色
                }
                tv.setBackgroundColor(color);
            });
        }
    }
    
    /**
     * 显示状态窗口（带会话信息）
     */
    public void showStatusWindow(String sessionId, String goal) {
        showStatusBar();
        updateStatus("运行中", STATUS_RUNNING);
    }
    
    /**
     * 隐藏状态窗口
     */
    public void hideStatusWindow() {
        hideStatusBar();
    }
    
    /**
     * 更新任务指标
     */
    public void updateMetrics(int step, double cost) {
        if (statusBarView != null && statusBarView instanceof TextView) {
            handler.post(() -> {
                TextView tv = (TextView) statusBarView;
                tv.setText("GhostTap: 步骤 " + step + " | 成本: $" + String.format("%.4f", cost));
            });
        }
    }
    
    /**
     * 更新状态（带步骤和成本）
     */
    public void updateStatus(int step, double cost, String action) {
        String text = "运行中 - 步骤: " + step;
        if (action != null) {
            text += " | 动作: " + action;
        }
        updateStatus(text, STATUS_RUNNING);
    }
    
    /**
     * 显示授权弹窗
     */
    public void showAuthDialog(String sessionId, String goal, int timeoutSeconds, AuthCallback callback) {
        // 隐藏状态栏避免冲突
        hideStatusBar();
        
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
            .setTitle("授权请求")
            .setMessage("AI 想要控制您的设备完成以下任务:\n\n" + goal + "\n\n是否允许？")
            .setCancelable(false);
        
        builder.setPositiveButton("允许", (dialog, which) -> {
            if (callback != null) callback.onAllowed();
        });
        
        builder.setNegativeButton("拒绝", (dialog, which) -> {
            if (callback != null) callback.onDenied();
            showStatusBar();
        });
        
        authDialog = builder.create();
        authDialog.show();
        
        // 设置超时自动拒绝
        if (timeoutSeconds > 0) {
            handler.postDelayed(() -> {
                if (authDialog != null && authDialog.isShowing()) {
                    authDialog.dismiss();
                    if (callback != null) callback.onDenied();
                    showStatusBar();
                }
            }, timeoutSeconds * 1000L);
        }
    }
    
    /**
     * 隐藏授权弹窗
     */
    public void hideAuthDialog() {
        if (authDialog != null) {
            authDialog.dismiss();
            authDialog = null;
        }
    }
    
    /**
     * 显示暂停对话框
     */
    public void showPauseDialog(String reason, final PauseCallback callback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
            .setTitle("任务已暂停")
            .setMessage(reason)
            .setCancelable(false);
        
        builder.setPositiveButton("继续", (dialog, which) -> {
            if (callback != null) callback.onResume();
        });
        
        builder.setNegativeButton("取消", (dialog, which) -> {
            if (callback != null) callback.onCancel();
        });
        
        pauseDialog = builder.create();
        pauseDialog.show();
    }
    
    /**
     * 隐藏暂停对话框
     */
    public void hidePauseDialog() {
        if (pauseDialog != null) {
            pauseDialog.dismiss();
            pauseDialog = null;
        }
    }
    
    /**
     * 显示任务完成提示
     */
    public void showTaskCompleted(String result) {
        new AlertDialog.Builder(context)
            .setTitle("✅ 任务完成")
            .setMessage(result)
            .setPositiveButton("确定", (dialog, which) -> {})
            .show();
        
        updateStatus("已完成", STATUS_IDLE);
    }
    
    /**
     * 显示任务失败提示
     */
    public void showTaskFailed(String reason) {
        new AlertDialog.Builder(context)
            .setTitle("❌ 任务失败")
            .setMessage(reason)
            .setPositiveButton("确定", (dialog, which) -> {})
            .show();
        
        updateStatus("已失败", STATUS_DISCONNECTED);
    }
    
    /**
     * 隐藏所有悬浮窗
     */
    public void hideAll() {
        hideStatusBar();
        hideAuthDialog();
        hidePauseDialog();
    }
    
    /**
     * 释放资源
     */
    public void release() {
        hideAll();
    }
    
    /**
     * 检查是否有悬浮窗权限
     */
    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }
    
    /**
     * dp 转 px
     */
    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
    
    /**
     * 暂停回调接口
     */
    public interface PauseCallback {
        void onResume();
        void onCancel();
    }
}
