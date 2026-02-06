package com.aska.ghosttap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.UUID;

/**
 * GhostTap 无障碍服务核心
 * 
 * 职责：
 * - 作为 AccessibilityService 接收系统 UI 事件
 * - 管理 WebSocket 连接
 * - 协调 UI 采集、指令执行、悬浮窗显示
 * - 处理任务生命周期（授权、执行、暂停、结束）
 */
public class GhostTapService extends AccessibilityService implements
        WebSocketManager.OnConnectedCallback,
        WebSocketManager.OnDisconnectedCallback,
        WebSocketManager.OnMessageCallback,
        FloatWindowManager.AuthCallback,
        FloatWindowManager.PauseCallback {
    
    private static final String TAG = Config.LOG_TAG + ".Service";
    
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
    
    // 任务状态
    private String currentSessionId;
    private String currentGoal;
    private TaskStatus taskStatus = TaskStatus.IDLE;
    private boolean isPaused = false;
    
    // UI 变化节流
    private long lastUiEventTime = 0;
    private final Handler uiEventHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingUiEvent;
    
    // 当前步骤和花费
    private int currentStep = 0;
    private double currentCost = 0.0;
    
    // 屏幕尺寸（简化处理，实际应该从 WindowManager 获取）
    private int screenWidth = 1080;
    private int screenHeight = 2400;
    
    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        IDLE,           // 空闲
        PENDING_AUTH,   // 等待用户授权
        RUNNING,        // 正在执行
        PAUSED,         // 已暂停
        COMPLETED,      // 已完成
        FAILED          // 失败
    }
    
    /**
     * 服务连接成功时调用
     */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        
        Log.i(TAG, "GhostTap service connected");
        
        // 设置服务信息
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                          AccessibilityEvent.TYPE_VIEW_CLICKED |
                          AccessibilityEvent.TYPE_VIEW_FOCUSED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        
        // 初始化组件
        String userId = getUserId();
        Config.setUserId(userId);
        
        webSocketManager = new WebSocketManager(this, this, this);
        uiCollector = new AccessibilityCollector();
        commandExecutor = new CommandExecutor(this);
        floatWindowManager = new FloatWindowManager(this);
        
        // 连接 WebSocket
        webSocketManager.connect(userId);
        
        // 设置实例
        instance = this;
        isRunning = true;
        
        Log.i(TAG, "GhostTap service initialized, userId: " + userId);
    }
    
    /**
     * 接收无障碍事件
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRunning || currentSessionId == null || isPaused) {
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
        
        // 清理资源
        isRunning = false;
        instance = null;
        
        // 停止任务
        if (taskStatus == TaskStatus.RUNNING) {
            endTask("cancelled", "服务已停止");
        }
        
        // 释放组件
        if (pendingUiEvent != null) {
            uiEventHandler.removeCallbacks(pendingUiEvent);
        }
        webSocketManager.release();
        floatWindowManager.release();
        
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
     * 收到消息回调
     */
    @Override
    public void onMessage(ServerMessage message) {
        if (message instanceof ControlRequestMessage) {
            ControlRequestMessage req = (ControlRequestMessage) message;
            handleControlRequest(req.session_id, req.goal, req.timeout_ms);
        } else if (message instanceof ActionCommandMessage) {
            ActionCommandMessage cmd = (ActionCommandMessage) message;
            handleActionCommand(cmd);
        } else if (message instanceof TaskEndMessage) {
            TaskEndMessage end = (TaskEndMessage) message;
            endTask(end.status, end.result);
        } else if (message instanceof TaskMetricsMessage) {
            TaskMetricsMessage metrics = (TaskMetricsMessage) message;
            handleMetrics(metrics);
        }
    }
    
    /**
     * 处理控制请求
     */
    private void handleControlRequest(String sessionId, String goal, int timeoutMs) {
        Log.i(TAG, "Control request received: session=" + sessionId + ", goal=" + goal);
        
        if (taskStatus != TaskStatus.IDLE) {
            // 已有任务在进行中，拒绝新请求
            webSocketManager.sendAuthorization(sessionId, "denied");
            return;
        }
        
        // 保存任务信息
        currentSessionId = sessionId;
        currentGoal = goal;
        taskStatus = TaskStatus.PENDING_AUTH;
        currentStep = 0;
        currentCost = 0.0;
        
        // 显示授权弹窗
        floatWindowManager.showAuthDialog(sessionId, goal, timeoutMs / 1000, this);
        
        // 设置超时自动拒绝
        mainHandler.postDelayed(() -> {
            if (taskStatus == TaskStatus.PENDING_AUTH) {
                onDenied();
            }
        }, timeoutMs);
    }
    
    /**
     * 处理动作命令
     */
    private void handleActionCommand(ActionCommandMessage cmd) {
        Log.i(TAG, "Action command: " + cmd.action + ", reason: " + cmd.reason);
        
        if (taskStatus != TaskStatus.RUNNING) {
            Log.w(TAG, "Received action but task not running");
            return;
        }
        
        // 更新悬浮窗显示
        floatWindowManager.updateStatus(currentStep, currentCost, cmd.action);
        
        // 执行动作
        executeAction(cmd);
    }
    
    /**
     * 执行动作
     */
    private void executeAction(ActionCommandMessage cmd) {
        boolean success = false;
        
        switch (cmd.action) {
            case "click":
                if (cmd.target != null && cmd.target.center != null && cmd.target.center.size() >= 2) {
                    int x = (int) (cmd.target.center.get(0) * screenWidth / 100);
                    int y = (int) (cmd.target.center.get(1) * screenHeight / 100);
                    success = commandExecutor.clickAt(x, y);
                }
                break;
            case "input":
                if (cmd.text != null) {
                    // 需要找到输入框节点
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        AccessibilityNodeInfo node = findFocusedInput(root);
                        if (node != null) {
                            success = commandExecutor.inputText(node, cmd.text);
                        }
                        root.recycle();
                    }
                }
                break;
            case "swipe_up":
                success = commandExecutor.swipeUp(screenWidth, screenHeight);
                break;
            case "swipe_down":
                success = commandExecutor.swipeDown(screenWidth, screenHeight);
                break;
            case "swipe_left":
                success = commandExecutor.swipeLeft(screenWidth, screenHeight);
                break;
            case "swipe_right":
                success = commandExecutor.swipeRight(screenWidth, screenHeight);
                break;
            case "back":
                success = commandExecutor.back();
                break;
            case "home":
                success = commandExecutor.home();
                break;
        }
        
        Log.d(TAG, "Action " + cmd.action + " result: " + success);
        
        // 动作完成后，等待一小段时间再采集 UI（给页面刷新时间）
        if (taskStatus == TaskStatus.RUNNING) {
            mainHandler.postDelayed(() -> {
                if (taskStatus == TaskStatus.RUNNING) {
                    sendUiEvent();
                }
            }, 300);
        }
    }
    
    /**
     * 查找聚焦的输入框
     */
    private AccessibilityNodeInfo findFocusedInput(AccessibilityNodeInfo node) {
        if (node.isFocused() && node.isEditable()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo result = findFocusedInput(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
    
    /**
     * 处理指标更新
     */
    private void handleMetrics(TaskMetricsMessage metrics) {
        this.currentStep = metrics.current_step;
        this.currentCost = metrics.current_cost;
        floatWindowManager.updateMetrics(metrics.current_step, metrics.current_cost);
    }
    
    /**
     * 授权 - 允许
     */
    @Override
    public void onAllowed() {
        Log.i(TAG, "User allowed control: " + currentSessionId);
        
        taskStatus = TaskStatus.RUNNING;
        
        // 发送授权响应
        webSocketManager.sendAuthorization(currentSessionId, "allowed");
        
        // 显示状态悬浮窗
        floatWindowManager.showStatusWindow(currentSessionId, currentGoal);
        
        // 立即采集并发送当前 UI
        sendUiEvent();
    }
    
    /**
     * 授权 - 拒绝
     */
    @Override
    public void onDenied() {
        Log.i(TAG, "User denied control: " + currentSessionId);
        
        // 发送拒绝响应
        if (currentSessionId != null) {
            webSocketManager.sendAuthorization(currentSessionId, "denied");
        }
        
        // 重置状态
        resetTaskState();
        floatWindowManager.showStatusBar();
    }
    
    /**
     * 暂停后继续
     */
    @Override
    public void onResume() {
        Log.i(TAG, "User resumed task");
        
        if (taskStatus != TaskStatus.PAUSED) return;
        
        isPaused = false;
        taskStatus = TaskStatus.RUNNING;
        
        if (currentSessionId != null) {
            webSocketManager.sendResume(currentSessionId);
        }
        
        // 重新显示状态窗口
        floatWindowManager.showStatusWindow(currentSessionId, currentGoal);
        
        // 发送当前 UI 状态
        sendUiEvent();
    }
    
    /**
     * 暂停后取消
     */
    @Override
    public void onCancel() {
        Log.i(TAG, "User cancelled task from pause");
        endTask("cancelled", "用户取消任务");
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
            sendUiEvent();
            lastUiEventTime = System.currentTimeMillis();
        };
        
        uiEventHandler.postDelayed(pendingUiEvent, delay);
    }
    
    /**
     * 采集并发送 UI 事件
     */
    private void sendUiEvent() {
        if (!webSocketManager.isConnected() || currentSessionId == null) {
            return;
        }
        
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        
        String packageName = root.getPackageName() != null ? root.getPackageName().toString() : "";
        String className = root.getClassName() != null ? root.getClassName().toString() : "";
        
        UiEventMessage uiEvent = uiCollector.collect(
            root,
            Config.getUserId(),
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
        // 隐藏悬浮窗
        floatWindowManager.release();
        
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
        isPaused = false;
        currentStep = 0;
        currentCost = 0.0;
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
     * 主动请求停止任务
     */
    public void stopCurrentTask() {
        if (currentSessionId != null) {
            endTask("cancelled", "用户主动停止");
        }
    }
    
    /**
     * 检查服务是否运行中
     */
    public static boolean isRunning() {
        return isRunning;
    }
    
    /**
     * 获取服务实例
     */
    public static GhostTapService getInstance() {
        return instance;
    }
}
