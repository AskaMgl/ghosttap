package com.aska.ghosttap;

/**
 * GhostTap 配置文件
 * 
 * 包含服务端连接配置、心跳间隔、重连策略等常量
 */
public class Config {
    
    // ========== 服务端配置 ==========
    
    /**
     * WebSocket 服务器地址
     * 使用 wss:// 进行加密传输
     */
    public static final String SERVER_URL = "wss://your-server.com/ws";
    
    /**
     * HTTP API 基础地址（用于获取配置等）
     */
    public static final String API_BASE_URL = "https://your-server.com";
    
    // ========== 用户配置 ==========
    
    /**
     * 用户唯一标识
     * 从 OpenClaw/Feishu 获取
     */
    private static String USER_ID = "";
    
    /**
     * 设备名称（用于显示）
     */
    public static String DEVICE_NAME = "Android设备";
    
    // ========== 心跳配置 ==========
    
    /**
     * 心跳间隔（毫秒）
     * 默认 3 分钟
     */
    public static final long HEARTBEAT_INTERVAL = 3 * 60 * 1000L;
    
    /**
     * 心跳超时时间（毫秒）
     * 超过此时间未收到 pong 则认为连接断开
     */
    public static final long HEARTBEAT_TIMEOUT = 5 * 60 * 1000L;
    
    // ========== 重连配置 ==========
    
    /**
     * 基础重连延迟（毫秒）
     * 使用指数退避：delay = baseDelay * 2^attempt
     */
    public static final long RECONNECT_BASE_DELAY = 1000L;
    
    /**
     * 最大重连延迟（毫秒）
     */
    public static final long RECONNECT_MAX_DELAY = 60000L;
    
    /**
     * UI 事件上报防抖时间（毫秒）
     * 防止界面频繁变化导致大量上报
     */
    public static final long UI_EVENT_DEBOUNCE = 300L;
    
    // ========== 悬浮窗配置 ==========
    
    /**
     * 授权弹窗超时时间（毫秒）
     */
    public static final long AUTH_TIMEOUT = 60000L;
    
    /**
     * 悬浮窗状态栏高度（dp）
     */
    public static final int FLOAT_WINDOW_HEIGHT = 60;
    
    // ========== UI 采集配置 ==========
    
    /**
     * 最大 UI 元素数量
     * 超过此数量将只保留最前面的元素
     */
    public static final int MAX_UI_ELEMENTS = 50;
    
    /**
     * 最小元素尺寸（百分比）
     * 小于此尺寸的元素将被过滤
     */
    public static final float MIN_ELEMENT_SIZE = 1.0f;
    
    // ========== 调试配置 ==========
    
    /**
     * 是否启用详细日志
     */
    public static boolean DEBUG_MODE = false;
    
    /**
     * 日志标签前缀
     */
    public static final String LOG_TAG = "GhostTap";
    
    /**
     * SharedPreferences 名称
     */
    public static final String PREFS_NAME = "ghosttap_prefs";
    
    /**
     * 用户 ID 存储键
     */
    public static final String PREF_USER_ID = "user_id";
    
    /**
     * 服务器地址存储键
     */
    public static final String PREF_SERVER_URL = "server_url";
    
    /**
     * UI 事件节流间隔（毫秒）
     */
    public static final long UI_EVENT_THROTTLE_MS = 300L;
    
    /**
     * 获取用户 ID
     */
    public static String getUserId() {
        if (USER_ID.isEmpty()) {
            throw new IllegalStateException("USER_ID not set");
        }
        return USER_ID;
    }
    
    /**
     * 设置用户 ID
     */
    public static void setUserId(String userId) {
        USER_ID = userId;
    }
}
