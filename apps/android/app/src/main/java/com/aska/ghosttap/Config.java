package com.aska.ghosttap;

import android.os.Build;

/**
 * GhostTap 配置文件 (v3.14)
 * 
 * 包含服务端连接配置、心跳间隔、重连策略等常量
 */
public class Config {
    
    // ========== 服务端配置 ==========
    
    /**
     * WebSocket 服务器地址
     * v3.14: MVP 阶段使用 ws:// (明文)，生产环境建议使用 wss:// (加密)
     * 认证信息通过 URL query 参数传递，不在 URL 中硬编码
     */
    public static final String SERVER_URL = "ws://your-server.com:8080";
    
    // ========== 用户配置 ==========
    
    /**
     * 用户唯一标识
     * 从 OpenClaw/Feishu 获取
     */
    private static String USER_ID = "";
    
    /**
     * 默认设备名称（用于显示）
     * v3.14: 自动读取系统设备名称
     */
    public static String getDefaultDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        
        // 移除大小写重复（如 "Xiaomi 22041211AC" 不需要变更）
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        }
        
        return capitalize(manufacturer) + " " + model;
    }
    
    /**
     * 首字母大写
     */
    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
    
    /**
     * 设备名称（运行时缓存）
     * v3.14: 默认使用系统设备名
     */
    private static String DEVICE_NAME = null;  // 懒加载，等待 getDefaultDeviceName()
    
    // ========== 心跳配置 (v3.12: 90秒间隔) ==========
    
    /**
     * 心跳间隔（毫秒）
     * v3.12: 90秒，适配部分运营商 NAT 超时低至 60-120 秒
     */
    public static final long HEARTBEAT_INTERVAL = 90 * 1000L;
    
    // ========== 重连配置 ==========
    
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
    
    /**
     * v3.14: 获取设备名称
     * 如果没有设置过，返回系统设备名
     */
    public static String getDeviceName() {
        if (DEVICE_NAME == null) {
            DEVICE_NAME = getDefaultDeviceName();
        }
        return DEVICE_NAME;
    }
    
    /**
     * v3.14: 设置设备名称
     */
    public static void setDeviceName(String deviceName) {
        DEVICE_NAME = deviceName != null && !deviceName.isEmpty() 
            ? deviceName 
            : getDefaultDeviceName();
    }
}
