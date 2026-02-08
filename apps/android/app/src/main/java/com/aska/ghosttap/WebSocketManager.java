package com.aska.ghosttap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * WebSocket 连接管理器 (v3.14)
 * 
 * 职责：
 * 1. 通过 URL query 参数进行连接认证
 * 2. 发送心跳保活（90秒间隔）
 * 3. 自动重连（指数退避，while循环避免栈溢出）
 * 4. 消息序列化和反序列化
 */
public class WebSocketManager {
    
    private static final String TAG = Config.LOG_TAG + ".WebSocket";
    
    // v3.12: 心跳配置
    private static final long HEARTBEAT_INTERVAL_MS = 90 * 1000L;  // 90秒
    private static final long PONG_TIMEOUT_MS = 5 * 1000L;         // 5秒等待pong
    private static final long MAX_RETRY_DELAY_MS = 60 * 1000L;     // 最大60秒
    
    private final OkHttpClient client;
    private final Handler handler;
    
    private WebSocket webSocket;
    private String userId = "";
    private String deviceName = "";
    private boolean isConnected = false;
    private volatile boolean shouldReconnect = true;
    
    // v3.12: 心跳状态跟踪
    private volatile long lastPingSentTime = 0L;   // 最近一次 ping 发送时间
    private volatile long lastPongTime = 0L;       // 最近一次 pong 接收时间
    private long retryDelay = 1000L;
    
    private Thread reconnectThread;
    private Runnable heartbeatRunnable;  // 心跳任务引用，用于精确取消
    
    private final OnConnectedCallback onConnected;
    private final OnDisconnectedCallback onDisconnected;
    private final OnMessageCallback onMessage;
    
    public interface OnConnectedCallback {
        void onConnected();
    }
    
    public interface OnDisconnectedCallback {
        void onDisconnected();
    }
    
    public interface OnMessageCallback {
        void onMessage(ServerMessage message);
    }
    
    public WebSocketManager(
            OnConnectedCallback onConnected,
            OnDisconnectedCallback onDisconnected,
            OnMessageCallback onMessage) {
        
        this.onConnected = onConnected;
        this.onDisconnected = onDisconnected;
        this.onMessage = onMessage;
        
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSocket 需要无限读取超时
            .writeTimeout(10, TimeUnit.SECONDS);
        
        if (Config.DEBUG_MODE) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            builder.addInterceptor(logging);
        }
        
        this.client = builder.build();
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 连接到 WebSocket 服务器 (v3.14: 自动使用系统设备名称)
     */
    public void connect(String userId, String deviceName, String serverUrl) {
        Log.i(TAG, "Connecting to WebSocket: userId=" + userId + ", serverUrl=" + serverUrl);
        
        if (userId == null || userId.isEmpty()) {
            Log.e(TAG, "Cannot connect: userId is empty");
            return;
        }
        
        this.userId = userId;
        this.currentServerUrl = serverUrl;
        // v3.14: 如果未指定设备名，使用系统设备名
        this.deviceName = (deviceName != null && !deviceName.isEmpty()) 
            ? deviceName 
            : Config.getDefaultDeviceName();
        
        if (isConnected) {
            Log.w(TAG, "Already connected");
            return;
        }
        
        shouldReconnect = true;
        
        try {
            // v3.14: 使用用户配置的服务器地址，默认使用 Config.SERVER_URL
            String baseUrl = (serverUrl != null && !serverUrl.isEmpty()) ? serverUrl : Config.SERVER_URL;
            // v3.12: 通过 URL query 参数传递认证信息
            String wsUrl = baseUrl + "?user_id=" + userId + "&device_name=" + this.deviceName;
            Log.i(TAG, "WebSocket URL: " + wsUrl);
            
            Request request = new Request.Builder()
                .url(wsUrl)
                .build();
        
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "WebSocket connected");
                isConnected = true;
                retryDelay = 1000L;  // 重置重连延迟
                
                // v3.12: 启动心跳（不再发送初始ping认证，等服务器发送pong）
                startHeartbeat();
                
                if (onConnected != null) {
                    handler.post(onConnected::onConnected);
                }
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (Config.DEBUG_MODE) {
                    Log.d(TAG, "Received: " + text);
                }
                handleMessage(text);
            }
            
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "WebSocket closing: " + code + " - " + reason);
                webSocket.close(code, reason);
            }
            
            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "WebSocket closed: " + code + " - " + reason);
                handleDisconnect();
            }
            
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket error", t);
                handleDisconnect();
            }
        });
        } catch (Exception e) {
            Log.e(TAG, "Failed to create WebSocket connection", e);
        }
    }
    
    /**
     * 重载：使用默认设备名称和默认服务器地址
     */
    public void connect(String userId) {
        connect(userId, Config.getDeviceName(), Config.SERVER_URL);
    }
    
    private String currentServerUrl;  // 保存当前服务器地址用于重连
    
    /**
     * 断开连接
     */
    public void disconnect() {
        Log.i(TAG, "Disconnecting...");
        
        shouldReconnect = false;
        stopHeartbeat();
        cancelReconnect();
        
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        
        isConnected = false;
        if (onDisconnected != null) {
            handler.post(onDisconnected::onDisconnected);
        }
    }
    
    /**
     * 发送 UI 事件
     */
    public void sendUiEvent(UiEventMessage event) {
        if (!isConnected) return;
        
        String message = JsonUtils.toJson(event);
        send(message);
    }
    
    /**
     * 发送暂停请求 (v3.12: 新增)
     */
    public void sendPauseRequest(String sessionId) {
        if (!isConnected) return;
        
        PauseRequest message = new PauseRequest(sessionId);
        send(JsonUtils.toJson(message));
    }
    
    /**
     * 发送恢复请求 (v3.12: 新增)
     */
    public void sendResumeRequest(String sessionId) {
        if (!isConnected) return;
        
        ResumeRequest message = new ResumeRequest(sessionId);
        send(JsonUtils.toJson(message));
    }
    
    /**
     * 发送停止请求 (v3.12: 新增)
     */
    public void sendStopRequest(String sessionId) {
        if (!isConnected) return;
        
        StopRequest message = new StopRequest(sessionId);
        send(JsonUtils.toJson(message));
    }
    
    /**
     * 发送错误事件 (v3.12: 新增)
     */
    public void sendErrorEvent(String sessionId, String errorCode, String errorMessage) {
        if (!isConnected) return;
        
        ErrorEvent message = new ErrorEvent(sessionId, errorCode, errorMessage);
        send(JsonUtils.toJson(message));
    }
    
    /**
     * 发送消息
     */
    private void send(String message) {
        if (Config.DEBUG_MODE) {
            Log.d(TAG, "Sending: " + message);
        }
        
        if (webSocket != null) {
            webSocket.send(message);
        }
    }
    
    /**
     * v3.12: 发送心跳（90秒间隔）
     */
    private void sendPing() {
        if (!isConnected) return;
        
        lastPingSentTime = System.currentTimeMillis();
        PingMessage ping = new PingMessage(lastPingSentTime);
        
        send(JsonUtils.toJson(ping));
        
        // 5秒后检查是否收到pong
        handler.postDelayed(() -> {
            if (isConnected && lastPongTime < lastPingSentTime) {
                Log.w(TAG, "Pong timeout, closing connection");
                // 未收到pong，关闭连接触发重连
                if (webSocket != null) {
                    webSocket.close(1001, "Pong timeout");
                }
            }
        }, PONG_TIMEOUT_MS);
    }
    
    /**
     * v3.12: 启动心跳定时器（90秒间隔）
     */
    private void startHeartbeat() {
        // 立即发送第一个ping
        sendPing();

        // 定时发送后续ping
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    sendPing();
                    handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
                }
            }
        };

        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    /**
     * 停止心跳
     */
    private void stopHeartbeat() {
        // 精确取消心跳任务，避免影响其他 Handler 消息
        if (heartbeatRunnable != null) {
            handler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }
    
    /**
     * 处理连接断开
     */
    private void handleDisconnect() {
        if (!isConnected && !shouldReconnect) return;
        
        isConnected = false;
        if (onDisconnected != null) {
            handler.post(onDisconnected::onDisconnected);
        }
        
        // 尝试重连
        if (shouldReconnect) {
            scheduleReconnect();
        }
    }
    
    /**
     * v3.12: 调度重连（while循环避免栈溢出）
     */
    private void scheduleReconnect() {
        if (reconnectThread != null && reconnectThread.isAlive()) {
            return;  // 已有重连线程在运行
        }
        
        reconnectThread = new Thread(() -> {
            while (shouldReconnect && !isConnected) {
                try {
                    Log.i(TAG, "Reconnecting in " + retryDelay + "ms");
                    Thread.sleep(retryDelay);
                    
                    // 指数退避
                    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_DELAY_MS);
                    
                    if (shouldReconnect && !isConnected) {
                        // v3.14: 使用保存的服务器地址进行重连
                        handler.post(() -> connect(userId, deviceName, currentServerUrl));
                        return;  // connect会创建新的WebSocket，退出循环
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "Reconnect thread interrupted");
                    return;
                }
            }
        });
        
        reconnectThread.start();
    }
    
    /**
     * 取消重连
     */
    private void cancelReconnect() {
        if (reconnectThread != null) {
            reconnectThread.interrupt();
            reconnectThread = null;
        }
    }
    
    /**
     * v3.12: 处理收到的消息（更新消息类型）
     */
    private void handleMessage(String text) {
        try {
            JSONObject jsonObject = new JSONObject(text);
            String type = jsonObject.optString("type", "");
            
            ServerMessage message;
            switch (type) {
                case "pong":
                    message = JsonUtils.fromJson(text, PongMessage.class);
                    // v3.12: 更新pong时间
                    if (message instanceof PongMessage) {
                        lastPongTime = System.currentTimeMillis();
                    }
                    break;
                    
                case "task_start":
                    message = JsonUtils.fromJson(text, TaskStart.class);
                    break;
                    
                case "task_resume":
                    message = JsonUtils.fromJson(text, TaskResume.class);
                    break;
                    
                case "action":
                    message = JsonUtils.fromJson(text, ActionCommandMessage.class);
                    break;
                    
                case "task_end":
                    message = JsonUtils.fromJson(text, TaskEnd.class);
                    break;
                    
                default:
                    Log.w(TAG, "Unknown message type: " + type);
                    return;
            }
            
            if (onMessage != null) {
                final ServerMessage finalMessage = message;
                handler.post(() -> onMessage.onMessage(finalMessage));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse message", e);
        }
    }
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        disconnect();
        client.dispatcher().executorService().shutdown();
    }
}
