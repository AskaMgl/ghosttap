package com.aska.ghosttap;

/**
 * GhostTap 配置文件 (v3.12)
 * 
 * 包含服务端连接配置、心跳间隔、重连策略等常量
 */
public class Config {
    
    // ========== 服务端配置 ==========
    
    /**
     * WebSocket 服务器地址
     * 使用 wss:// 进行加密传输
     * v3.12: 认证信息通过 URL query 参数传递，不在 URL 中硬编码
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
     * v3.12: 连接时上报给服务器
     */
    public static String DEVICE_NAME = "Android设备";
    
    // ========== 心跳配置 (v3.12: 90秒间隔) ==========
    
    /**
     * 心跳间隔（毫秒）
     * v3.12: 90秒，适配部分运营商 NAT 超时低至 60-120 秒
     */
    public static final long HEARTBEAT_INTERVAL = 90 * 1000L;
    
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
     * v3.12: 300ms，合并连续事件
     */
    public static final long UI_EVENT_DEBOUNCE = 300L;
    
    // ========== UI 采集配置 ==========
    
    /**
     * 最大 UI 元素数量
     * v3.12: 50个，超过将截断并设置 truncated=true
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
     * 设备名称存储键
     */
    public static final String PREF_DEVICE_NAME = "device_name";
    
    // ========== 方法 ==========
    
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
