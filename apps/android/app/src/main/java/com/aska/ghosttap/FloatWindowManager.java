package com.aska.ghosttap;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗管理器 (v3.12)
 * 
 * 职责：
 * 1. 显示任务状态悬浮窗（简化设计：迷你卡片）
 * 2. 显示运行中状态（带暂停/结束按钮）
 * 3. 显示暂停状态（带继续/结束按钮）
 * 4. 支持拖动到屏幕任意位置
 * 5. 无任务时自动隐藏
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
    private final LayoutInflater inflater;
    
    // 悬浮窗视图
    private View floatWindowView;
    private WindowManager.LayoutParams floatWindowParams;
    
    // 视图组件
    private TextView tvStatus;
    private TextView tvGoal;
    private Button btnAction1;  // 暂停/继续
    private Button btnAction2;  // 结束
    
    // 当前状态
    private int currentStatus = STATUS_IDLE;
    private String currentSessionId;
    private String currentGoal;
    
    // 拖动相关
    private float initialX;
    private float initialY;
    private float initialTouchX;
    private float initialTouchY;
    
    // 回调
    private PauseCallback pauseCallback;
    
    /**
     * 暂停回调接口 (v3.12)
     */
    public interface PauseCallback {
        void onUserPause(String reason);
        void onUserResume();
        void onUserStop();
    }
    
    public FloatWindowManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
        this.inflater = LayoutInflater.from(context);
    }
    
    /**
     * 设置暂停回调
     */
    public void setPauseCallback(PauseCallback callback) {
        this.pauseCallback = callback;
    }
    
    /**
     * v3.12: 显示运行中状态
     */
    public void showRunning(String sessionId, String goal) {
        currentSessionId = sessionId;
        currentGoal = goal;
        currentStatus = STATUS_RUNNING;
        
        handler.post(() -> {
            ensureFloatWindowCreated();
            updateRunningUI();
            showFloatWindow();
        });
    }
    
    /**
     * v3.12: 显示暂停状态
     */
    public void showPaused(String reason) {
        currentStatus = STATUS_PAUSED;
        
        handler.post(() -> {
            ensureFloatWindowCreated();
            updatePausedUI(reason);
            showFloatWindow();
        });
    }
    
    /**
     * v3.12: 隐藏悬浮窗
     */
    public void hide() {
        currentStatus = STATUS_IDLE;
        currentSessionId = null;
        currentGoal = null;
        
        handler.post(() -> {
            if (floatWindowView != null) {
                try {
                    windowManager.removeView(floatWindowView);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to remove float window", e);
                }
                floatWindowView = null;
                floatWindowParams = null;
            }
        });
    }
    
    /**
     * 更新状态显示（兼容旧接口）
     */
    public void updateStatus(String statusText, int status) {
        currentStatus = status;
        
        if (floatWindowView != null) {
            handler.post(() -> {
                if (tvStatus != null) {
                    tvStatus.setText("GhostTap: " + statusText);
                }
                
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
                if (floatWindowView != null) {
                    floatWindowView.setBackgroundColor(color);
                }
            });
        }
    }
    
    /**
     * 确保悬浮窗已创建
     */
    private void ensureFloatWindowCreated() {
        if (floatWindowView != null) return;
        if (!canDrawOverlays()) {
            Log.w(TAG, "Cannot draw overlays");
            return;
        }
        
        // 创建悬浮窗视图
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 15, 20, 15);
        layout.setBackgroundColor(0xFF4CAF50); // 默认绿色
        
        // 状态文本
        tvStatus = new TextView(context);
        tvStatus.setTextColor(0xFFFFFFFF);
        tvStatus.setTextSize(14);
        tvStatus.setText("GhostTap: 运行中");
        layout.addView(tvStatus);
        
        // 目标文本
        tvGoal = new TextView(context);
        tvGoal.setTextColor(0xCCFFFFFF);
        tvGoal.setTextSize(12);
        tvGoal.setMaxLines(1);
        layout.addView(tvGoal);
        
        // 按钮布局
        LinearLayout btnLayout = new LinearLayout(context);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setPadding(0, 10, 0, 0);
        
        // 按钮1（暂停/继续）
        btnAction1 = new Button(context);
        btnAction1.setText("暂停");
        btnAction1.setTextSize(12);
        btnAction1.setPadding(10, 5, 10, 5);
        LinearLayout.LayoutParams btnParams1 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams1.setMargins(0, 0, 10, 0);
        btnLayout.addView(btnAction1, btnParams1);
        
        // 按钮2（结束）
        btnAction2 = new Button(context);
        btnAction2.setText("结束");
        btnAction2.setTextSize(12);
        btnAction2.setPadding(10, 5, 10, 5);
        btnLayout.addView(btnAction2);
        
        layout.addView(btnLayout);
        
        floatWindowView = layout;
        
        // 设置按钮点击事件
        setupButtonListeners();
        
        // 设置拖动
        setupDrag();
        
        // 创建布局参数
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
            WindowManager.LayoutParams.TYPE_PHONE;
        
        floatWindowParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        floatWindowParams.gravity = Gravity.TOP | Gravity.START;
        floatWindowParams.x = 20;
        floatWindowParams.y = 100;
    }
    
    /**
     * 设置按钮点击事件
     */
    private void setupButtonListeners() {
        // 暂停/继续按钮
        btnAction1.setOnClickListener(v -> {
            if (pauseCallback == null) return;
            
            if (currentStatus == STATUS_RUNNING) {
                // 暂停
                pauseCallback.onUserPause("用户暂停");
            } else if (currentStatus == STATUS_PAUSED) {
                // 继续
                pauseCallback.onUserResume();
            }
        });
        
        // 结束按钮
        btnAction2.setOnClickListener(v -> {
            if (pauseCallback != null) {
                pauseCallback.onUserStop();
            }
        });
    }
    
    /**
     * 设置拖动功能
     */
    private void setupDrag() {
        floatWindowView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = floatWindowParams.x;
                    initialY = floatWindowParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                    
                case MotionEvent.ACTION_MOVE:
                    floatWindowParams.x = (int) (initialX + (event.getRawX() - initialTouchX));
                    floatWindowParams.y = (int) (initialY + (event.getRawY() - initialTouchY));
                    windowManager.updateViewLayout(floatWindowView, floatWindowParams);
                    return true;
                    
                default:
                    return false;
            }
        });
    }
    
    /**
     * 更新运行中状态UI
     */
    private void updateRunningUI() {
        if (floatWindowView == null) return;
        
        floatWindowView.setBackgroundColor(0xFF4CAF50); // 绿色
        tvStatus.setText("🤖 " + truncateText(currentGoal, 20));
        tvGoal.setText("运行中...");
        btnAction1.setText("暂停");
    }
    
    /**
     * 更新暂停状态UI
     */
    private void updatePausedUI(String reason) {
        if (floatWindowView == null) return;
        
        floatWindowView.setBackgroundColor(0xFFFFC107); // 黄色
        tvStatus.setText("⏸️ " + truncateText(currentGoal, 20));
        tvGoal.setText(reason != null ? truncateText(reason, 25) : "已暂停");
        btnAction1.setText("继续");
    }
    
    /**
     * 显示悬浮窗
     */
    private void showFloatWindow() {
        if (floatWindowView == null || floatWindowView.getParent() != null) return;
        
        try {
            windowManager.addView(floatWindowView, floatWindowParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show float window", e);
        }
    }
    
    /**
     * 截断文本
     */
    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
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
     * 释放资源
     */
    public void release() {
        hide();
    }
    
    // ========== 兼容旧接口（标记为废弃）==========
    
    @Deprecated
    public void showStatusBar() {
        // 不再使用
    }
    
    @Deprecated
    public void hideStatusBar() {
        hide();
    }
    
    @Deprecated
    public void showStatusWindow(String sessionId, String goal) {
        showRunning(sessionId, goal);
    }
    
    @Deprecated
    public void hideStatusWindow() {
        hide();
    }
    
    @Deprecated
    public void updateMetrics(int step, double cost) {
        // 不再使用
    }
    
    @Deprecated
    public void updateStatus(int step, double cost, String action) {
        // 不再使用
    }
    
    @Deprecated
    public void showAuthDialog(String sessionId, String goal, int timeoutSeconds, AuthCallback callback) {
        // v3.12: 移除授权流程，此方法不再使用
        Log.w(TAG, "showAuthDialog is deprecated in v3.12");
    }
    
    @Deprecated
    public void hideAuthDialog() {
        // 不再使用
    }
    
    @Deprecated
    public void showPauseDialog(String reason, final PauseCallbackOld callback) {
        // v3.12: 使用悬浮窗替代对话框
        Log.w(TAG, "showPauseDialog is deprecated in v3.12, use showPaused instead");
    }
    
    @Deprecated
    public void hidePauseDialog() {
        // 不再使用
    }
    
    @Deprecated
    public void showTaskCompleted(String result) {
        // v3.12: 不再显示弹窗，任务结束自动隐藏
        hide();
    }
    
    @Deprecated
    public void showTaskFailed(String reason) {
        // v3.12: 不再显示弹窗，任务结束自动隐藏
        hide();
    }
    
    @Deprecated
    public void hideAll() {
        hide();
    }
    
    /**
     * 旧的暂停回调接口（废弃）
     */
    @Deprecated
    public interface PauseCallbackOld {
        void onResume();
        void onCancel();
    }
    
    /**
     * 旧的授权回调接口（废弃）
     */
    @Deprecated
    public interface AuthCallback {
        void onAllowed();
        void onDenied();
    }
}
