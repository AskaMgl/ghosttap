package com.aska.ghosttap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.UUID;

/**
 * GhostTap 无障碍服务核心 (v3.12)
 * 
 * 职责：
 * - 作为 AccessibilityService 接收系统 UI 事件
 * - 管理 WebSocket 连接
 * - 协调 UI 采集、指令执行、悬浮窗显示
 * - 处理任务生命周期（v3.12: 移除授权流程，任务创建后直接开始）
 */
public class GhostTapService extends AccessibilityService implements
        WebSocketManager.OnConnectedCallback,
        WebSocketManager.OnDisconnectedCallback,
        WebSocketManager.OnMessageCallback,
        FloatWindowManager.PauseCallback {
    
    private static final String TAG = Config.LOG_TAG + ".Service";
    
    // v3.12: ForegroundService 通知
    private static final String NOTIFICATION_CHANNEL_ID = "ghosttap_channel";
    private static final int NOTIFICATION_ID = 1;
    
    // 服务运行状态
    private static volatile boolean isRunning = false;
    
    // 单例实例（用于外部访问）
    private static GhostTapService instance;
    
    // 核心组件
    private WebSocketManager webSocketManager;
    private AccessibilityCollector uiCollector;
    private CommandExecutor commandExecutor;
    private FloatWindowManager floatWindowManager;
    
    // 主线程 Handler
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // v3.12: 任务状态
    private String currentSessionId;
    private String currentGoal;
    private TaskStatus taskStatus = TaskStatus.IDLE;
    private boolean isUiReporting = false;  // 是否正在上报 UI
    
    // UI 变化节流
    private long lastUiEventTime = 0;
    private final Handler uiEventHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingUiEvent;
    
    // 屏幕尺寸
    private int screenWidth = 1080;
    private int screenHeight = 2400;
    
    /**
     * 任务状态枚举 (v3.12: 移除 PENDING_AUTH)
     */
    public enum TaskStatus {
        IDLE,           // 空闲
        RUNNING,        // 正在执行
        PAUSED,         // 已暂停
        COMPLETED,      // 已完成
        FAILED          // 失败
    }
    
    /**
     * v3.12: Service 创建时启动 ForegroundService
     */
    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundService();
    }
    
    /**
     * v3.14: 处理通知按钮点击
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            Log.i(TAG, "Stop service requested from notification");
            stopCurrentTask();
            stopSelf();
        }
        return super.onStartCommand(intent, flags, startId);
    }
    
    /**
     * v3.14: 启动前台服务，带停止按钮
     */
    private void startForegroundService() {
        // 创建通知渠道（Android O+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GhostTap 服务",
                NotificationManager.IMPORTANCE_LOW  // 低优先级，不发声不振动
            );
            channel.setDescription("GhostTap 远程控制服务运行中");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        
        // 创建停止服务的 PendingIntent
        Intent stopIntent = new Intent(this, GhostTapService.class);
        stopIntent.setAction("STOP_SERVICE");
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        );
        
        // 创建通知
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        
        Notification notification = builder
            .setContentTitle("GhostTap 运行中")
            .setContentText("AI 远程控制服务正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_compass)  // 使用系统图标
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止服务", stopPendingIntent)
            .build();
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, notification);
        Log.i(TAG, "Foreground service started");
    }
    
    /**
     * 服务连接成功时调用
     */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        
        Log.i(TAG, "GhostTap service connected");
        
        try {
            // 设置服务信息
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                              AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                              AccessibilityEvent.TYPE_VIEW_CLICKED |
                              AccessibilityEvent.TYPE_VIEW_FOCUSED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 100;
            // v3.12: 添加 flagRetrieveInteractiveWindows 用于软键盘检测
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                         AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
            
            // 获取屏幕尺寸
            updateScreenSize();
            
            // 初始化组件
            String userId = getUserId();
            String deviceName = getDeviceName();
            String serverUrl = getServerUrl();  // v3.14: 读取服务器地址
            Log.i(TAG, "Initializing with serverUrl: " + serverUrl + ", userId: " + userId);
            
            Config.setUserId(userId);
            Config.setDeviceName(deviceName);
            
            webSocketManager = new WebSocketManager(this, this, this);
            uiCollector = new AccessibilityCollector();
            commandExecutor = new CommandExecutor(this);
            floatWindowManager = new FloatWindowManager(this);
            floatWindowManager.setPauseCallback(this);
            
            // v3.14: 连接 WebSocket（传递 device_name 和 server_url）
            webSocketManager.connect(userId, deviceName, serverUrl);
            
            // 设置实例
            instance = this;
            isRunning = true;
            
            Log.i(TAG, "GhostTap service initialized, userId: " + userId);
        } catch (Exception e) {
            Log.e(TAG, "Error in onServiceConnected", e);
            throw e;  // 重新抛出以便看到堆栈跟踪
        }
    }
    
    /**
     * 获取屏幕尺寸
     */
    private void updateScreenSize() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                Display display = wm.getDefaultDisplay();
                Point size = new Point();
                display.getRealSize(size);
                screenWidth = size.x;
                screenHeight = size.y;
                Log.i(TAG, "Screen size: " + screenWidth + "x" + screenHeight);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get screen size", e);
        }
    }
    
    /**
     * 接收无障碍事件
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning) return;
        
        // v3.12: 只在 RUNNING 状态且开启上报时处理
        if (taskStatus != TaskStatus.RUNNING || !isUiReporting) {
            return;
        }
        
        // 只处理内容变化和窗口变化
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                // 节流处理，避免频繁上报
                throttleUiEvent();
                break;
        }
    }
    
    /**
     * 服务中断时调用
     */
    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted");
    }
    
    /**
     * 服务销毁时调用
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        Log.i(TAG, "GhostTap service destroying...");
        
        // 停止前台服务
        stopForeground(true);
        
        // 清理资源
        isRunning = false;
        instance = null;
        
        // 结束任务
        if (taskStatus == TaskStatus.RUNNING || taskStatus == TaskStatus.PAUSED) {
            endTask("cancelled", "服务已停止");
        }
        
        // 释放组件
        if (pendingUiEvent != null) {
            uiEventHandler.removeCallbacks(pendingUiEvent);
        }
        if (webSocketManager != null) {
            webSocketManager.release();
        }
        if (floatWindowManager != null) {
            floatWindowManager.release();
        }
        
        Log.i(TAG, "GhostTap service destroyed");
    }
    
    /**
     * WebSocket 连接成功回调
     */
    @Override
    public void onConnected() {
        Log.i(TAG, "WebSocket connected");
        floatWindowManager.updateStatus("已连接", FloatWindowManager.STATUS_CONNECTED);
    }
    
    /**
     * WebSocket 断开回调
     */
    @Override
    public void onDisconnected() {
        Log.w(TAG, "WebSocket disconnected");
        floatWindowManager.updateStatus("已断开", FloatWindowManager.STATUS_DISCONNECTED);
    }
    
    /**
     * v3.12: 收到消息回调（更新消息类型处理）
     */
    @Override
    public void onMessage(ServerMessage message) {
        if (message instanceof TaskStart) {
            TaskStart start = (TaskStart) message;
            handleTaskStart(start.session_id, start.goal);
        } else if (message instanceof TaskResume) {
            TaskResume resume = (TaskResume) message;
            handleTaskResume(resume);
        } else if (message instanceof ActionCommandMessage) {
            ActionCommandMessage cmd = (ActionCommandMessage) message;
            handleActionCommand(cmd);
        } else if (message instanceof TaskEnd) {
            TaskEnd end = (TaskEnd) message;
            handleTaskEnd(end.status, end.result);
        }
    }
    
    /**
     * v3.12: 处理任务开始
     */
    private void handleTaskStart(String sessionId, String goal) {
        Log.i(TAG, "Task start: session=" + sessionId + ", goal=" + goal);
        
        // 保存任务信息
        currentSessionId = sessionId;
        currentGoal = goal;
        taskStatus = TaskStatus.RUNNING;
        isUiReporting = true;
        
        // 显示悬浮窗
        floatWindowManager.showRunning(sessionId, goal);
        
        // 立即上报当前 UI
        reportCurrentUi();
    }
    
    /**
     * v3.12: 处理任务恢复（断连重连）
     */
    private void handleTaskResume(TaskResume resume) {
        Log.i(TAG, "Task resume: session=" + resume.session_id + ", status=" + resume.status);
        
        currentSessionId = resume.session_id;
        currentGoal = resume.goal;
        
        if ("running".equals(resume.status)) {
            taskStatus = TaskStatus.RUNNING;
            isUiReporting = true;
            floatWindowManager.showRunning(resume.session_id, resume.goal);
            reportCurrentUi();  // 立即上报当前 UI
        } else if ("paused".equals(resume.status)) {
            taskStatus = TaskStatus.PAUSED;
            isUiReporting = false;
            floatWindowManager.showPaused(resume.reason != null ? resume.reason : "已暂停");
        }
    }
    
    /**
     * v3.12: 处理动作命令
     */
    private void handleActionCommand(ActionCommandMessage cmd) {
        Log.i(TAG, "Action command: " + cmd.action + ", reason: " + cmd.reason);
        
        if (taskStatus != TaskStatus.RUNNING) {
            Log.w(TAG, "Received action but task not running, status=" + taskStatus);
            return;
        }
        
        // 执行动作
        executeAction(cmd);
    }
    
    /**
     * v3.12: 执行动作（支持新动作类型）
     */
    private void executeAction(ActionCommandMessage cmd) {
        boolean success = false;
        String errorCode = null;
        String errorMessage = null;
        
        switch (cmd.action) {
            case "click":
                if (cmd.target != null && cmd.target.center != null && cmd.target.center.size() >= 2) {
                    success = commandExecutor.clickAtPercent(
                        cmd.target.center.get(0), 
                        cmd.target.center.get(1),
                        screenWidth, 
                        screenHeight
                    );
                }
                break;
                
            case "input":
                if (cmd.text != null && cmd.target != null && cmd.target.center != null) {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        success = commandExecutor.inputText(
                            cmd.target.center.get(0), 
                            cmd.target.center.get(1),
                            cmd.text,
                            root,
                            screenWidth, 
                            screenHeight
                        );
                        root.recycle();
                    }
                }
                break;
                
            case "swipe":
                if (cmd.direction != null) {
                    Float centerX = cmd.target != null && cmd.target.center != null 
                        ? cmd.target.center.get(0) : null;
                    Float centerY = cmd.target != null && cmd.target.center != null 
                        ? cmd.target.center.get(1) : null;
                    
                    success = commandExecutor.swipe(
                        cmd.direction,
                        centerX,
                        centerY,
                        cmd.distance,
                        cmd.duration_ms,
                        screenWidth, 
                        screenHeight
                    );
                }
                break;
                
            case "back":
                success = commandExecutor.back();
                break;
                
            case "home":
                success = commandExecutor.home();
                break;
                
            case "launch_app":
                if (cmd.package_name != null) {
                    success = commandExecutor.launchApp(cmd.package_name);
                    if (!success) {
                        errorCode = "PACKAGE_NOT_FOUND";
                        errorMessage = "APP未安装: " + cmd.package_name;
                    }
                }
                break;
                
            case "wait":
                // wait 动作：等待指定时间后重新上报 UI
                int waitMs = cmd.wait_ms != null ? cmd.wait_ms : 1000;
                mainHandler.postDelayed(() -> {
                    if (taskStatus == TaskStatus.RUNNING) {
                        reportCurrentUi();
                    }
                }, waitMs);
                success = true;  // wait 立即返回成功
                break;
                
            case "pause":
                // AI 发起的暂停
                onUserPause(cmd.reason != null ? cmd.reason : "AI 暂停");
                success = true;
                break;
                
            default:
                Log.w(TAG, "Unknown action: " + cmd.action);
        }
        
        Log.d(TAG, "Action " + cmd.action + " result: " + success);
        
        // 上报错误
        if (!success && errorCode != null && currentSessionId != null) {
            webSocketManager.sendErrorEvent(currentSessionId, errorCode, errorMessage);
        }
    }
    
    /**
     * v3.12: 处理任务结束
     */
    private void handleTaskEnd(String status, String result) {
        Log.i(TAG, "Task end: status=" + status + ", result=" + result);
        
        // 隐藏悬浮窗
        floatWindowManager.hide();
        
        // 重置状态
        resetTaskState();
    }
    
    /**
     * v3.12: 用户暂停（点击悬浮窗暂停按钮）
     */
    @Override
    public void onUserPause(String reason) {
        Log.i(TAG, "User paused task: " + reason);
        
        if (taskStatus != TaskStatus.RUNNING) return;
        
        taskStatus = TaskStatus.PAUSED;
        stopUiReporting();
        
        // 发送暂停请求到云端
        if (currentSessionId != null) {
            webSocketManager.sendPauseRequest(currentSessionId);
        }
        
        // 更新悬浮窗
        floatWindowManager.showPaused(reason);
    }
    
    /**
     * v3.12: 用户继续（点击悬浮窗继续按钮）
     */
    @Override
    public void onUserResume() {
        Log.i(TAG, "User resumed task");
        
        if (taskStatus != TaskStatus.PAUSED) return;
        
        taskStatus = TaskStatus.RUNNING;
        isUiReporting = true;
        
        // 发送恢复请求到云端
        if (currentSessionId != null) {
            webSocketManager.sendResumeRequest(currentSessionId);
        }
        
        // 更新悬浮窗
        floatWindowManager.showRunning(currentSessionId, currentGoal);
        
        // 立即上报当前 UI
        reportCurrentUi();
    }
    
    /**
     * v3.12: 用户结束任务（点击悬浮窗结束按钮）
     */
    @Override
    public void onUserStop() {
        Log.i(TAG, "User stopped task");
        
        if (currentSessionId != null) {
            webSocketManager.sendStopRequest(currentSessionId);
        }
        
        endTask("cancelled", "用户结束任务");
    }
    
    /**
     * v3.12: 停止 UI 上报
     */
    public void stopUiReporting() {
        isUiReporting = false;
        
        // 取消待上报的 UI 事件
        if (pendingUiEvent != null) {
            uiEventHandler.removeCallbacks(pendingUiEvent);
            pendingUiEvent = null;
        }
    }
    
    /**
     * UI 事件节流处理
     */
    private void throttleUiEvent() {
        if (pendingUiEvent != null) {
            uiEventHandler.removeCallbacks(pendingUiEvent);
        }
        
        long now = System.currentTimeMillis();
        long delay = (now - lastUiEventTime < Config.UI_EVENT_DEBOUNCE) ?
            Config.UI_EVENT_DEBOUNCE - (now - lastUiEventTime) : 0;
        
        pendingUiEvent = () -> {
            reportCurrentUi();
            lastUiEventTime = System.currentTimeMillis();
        };
        
        uiEventHandler.postDelayed(pendingUiEvent, delay);
    }
    
    /**
     * v3.12: 采集并发送当前 UI
     * v3.13: 使用 getApplicationWindowRoot 过滤 OVERLAY 窗口
     */
    public void reportCurrentUi() {
        if (!webSocketManager.isConnected() || currentSessionId == null) {
            return;
        }
        
        // v3.13: 使用应用程序窗口根节点，过滤系统覆盖层
        AccessibilityNodeInfo root = uiCollector.getApplicationWindowRoot(this);
        if (root == null) {
            Log.w(TAG, "No application window root found");
            return;
        }
        
        String packageName = root.getPackageName() != null ? root.getPackageName().toString() : "";
        String className = root.getClassName() != null ? root.getClassName().toString() : "";
        
        // v3.12: 传递 service 用于软键盘检测
        UiEventMessage uiEvent = uiCollector.collect(
            this,  // AccessibilityService
            root,
            currentSessionId,
            packageName,
            className,
            screenWidth,
            screenHeight
        );
        
        root.recycle();
        
        webSocketManager.sendUiEvent(uiEvent);
    }
    
    /**
     * 结束任务
     */
    private void endTask(String status, String result) {
        // 停止 UI 上报
        stopUiReporting();
        
        // 隐藏悬浮窗
        if (floatWindowManager != null) {
            floatWindowManager.hide();
        }
        
        // 重置状态
        resetTaskState();
        
        Log.i(TAG, "Task ended with status: " + status + ", result: " + result);
    }
    
    /**
     * 重置任务状态
     */
    private void resetTaskState() {
        currentSessionId = null;
        currentGoal = null;
        taskStatus = TaskStatus.IDLE;
        isUiReporting = false;
        lastUiEventTime = 0;
    }
    
    /**
     * 获取用户 ID
     */
    private String getUserId() {
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        String userId = prefs.getString(Config.PREF_USER_ID, null);
        
        if (userId == null) {
            // 生成新的用户 ID
            userId = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            prefs.edit().putString(Config.PREF_USER_ID, userId).apply();
        }
        
        return userId;
    }
    
    /**
     * v3.14: 获取设备名称（支持持久化，默认使用系统设备名）
     */
    private String getDeviceName() {
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        String deviceName = prefs.getString(Config.PREF_DEVICE_NAME, null);
        
        // v3.14: 如果从未保存过，或保存的是旧版本默认值"安卓设备"，更新为系统设备名
        if (deviceName == null || deviceName.equals("Android设夁")) {
            deviceName = Config.getDefaultDeviceName();
            // 保存到 SharedPreferences
            prefs.edit().putString(Config.PREF_DEVICE_NAME, deviceName).apply();
        }
        
        return deviceName;
    }
    
    /**
     * v3.14: 获取服务器地址
     */
    private String getServerUrl() {
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        String url = prefs.getString(Config.PREF_SERVER_URL, Config.SERVER_URL);
        Log.d(TAG, "getServerUrl: " + url);
        return url;
    }
    
    /**
     * 获取当前任务状态
     */
    public TaskStatus getCurrentStatus() {
        return taskStatus;
    }
    
    /**
     * 获取当前会话 ID
     */
    public String getCurrentSessionId() {
        return currentSessionId;
    }
    
    /**
     * 检查服务是否运行中
     * 同时检查实例是否存在，防止服务崩溃后状态不一致
     */
    public static boolean isRunning() {
        return isRunning && instance != null;
    }
    
    /**
     * 获取服务实例
     */
    public static GhostTapService getInstance() {
        return instance;
    }
    
    /**
     * 检查 WebSocket 是否已连接
     */
    public boolean isWebSocketConnected() {
        return webSocketManager != null && webSocketManager.isConnected();
    }
    
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
}
