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
 * WebSocket 连接管理器
 * 
 * 职责：
 * 1. 建立和维护 WebSocket 连接
 * 2. 发送心跳保活
 * 3. 自动重连（指数退避）
 * 4. 消息序列化和反序列化
 */
public class WebSocketManager {
    
    private static final String TAG = Config.LOG_TAG + ".WebSocket";
    
    private final OkHttpClient client;
    private final Handler handler;
    
    private WebSocket webSocket;
    private String userId = "";
    private boolean isConnected = false;
    private int reconnectAttempt = 0;
    
    private Runnable heartbeatRunnable;
    private Runnable reconnectRunnable;
    
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
            .pingInterval(Config.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS); // WebSocket 需要无限读取超时
        
        if (Config.DEBUG_MODE) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            builder.addInterceptor(logging);
        }
        
        this.client = builder.build();
        this.handler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 连接到 WebSocket 服务器
     */
    public void connect(String userId) {
        this.userId = userId;
        
        if (isConnected) {
            Log.w(TAG, "Already connected");
            return;
        }
        
        Request request = new Request.Builder()
            .url(Config.SERVER_URL)
            .build();
        
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "WebSocket connected");
                isConnected = true;
                reconnectAttempt = 0;
                
                // 发送初始 ping 进行认证
                sendPing();
                
                // 启动心跳
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
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        Log.i(TAG, "Disconnecting...");
        
        stopHeartbeat();
        cancelReconnect();
        
        if (webSocket != null) {
            webSocket.close(1000, "Client disconnect");
            webSocket = null;
        }
        
        isConnected = false;
        if (onDisconnected != null) {
            onDisconnected.onDisconnected();
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
     * 发送授权响应
     */
    public void sendAuthorization(String sessionId, String decision) {
        if (!isConnected) return;
        
        AuthorizationMessage message = new AuthorizationMessage(
            userId,
            sessionId,
            decision
        );
        
        send(JsonUtils.toJson(message));
    }
    
    /**
     * 发送恢复任务消息
     */
    public void sendResume(String sessionId) {
        if (!isConnected) return;
        
        ResumeMessage message = new ResumeMessage(
            userId,
            sessionId
        );
        
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
     * 发送心跳
     */
    private void sendPing() {
        PingMessage ping = new PingMessage(
            userId,
            System.currentTimeMillis()
        );
        
        send(JsonUtils.toJson(ping));
    }
    
    /**
     * 启动心跳定时器
     */
    private void startHeartbeat() {
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (isConnected) {
                    sendPing();
                    handler.postDelayed(this, Config.HEARTBEAT_INTERVAL);
                }
            }
        };
        
        handler.postDelayed(heartbeatRunnable, Config.HEARTBEAT_INTERVAL);
    }
    
    /**
     * 停止心跳
     */
    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            handler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }
    
    /**
     * 处理连接断开
     */
    private void handleDisconnect() {
        if (!isConnected) return;
        
        isConnected = false;
        if (onDisconnected != null) {
            onDisconnected.onDisconnected();
        }
        
        // 尝试重连
        scheduleReconnect();
    }
    
    /**
     * 调度重连
     */
    private void scheduleReconnect() {
        if (reconnectRunnable != null) return;
        
        // 计算重连延迟（指数退避）
        long delay = Math.min(
            Config.RECONNECT_BASE_DELAY * (1L << reconnectAttempt),
            Config.RECONNECT_MAX_DELAY
        );
        
        Log.i(TAG, "Reconnecting in " + delay + "ms (attempt " + reconnectAttempt + ")");
        
        reconnectRunnable = new Runnable() {
            @Override
            public void run() {
                reconnectRunnable = null;
                reconnectAttempt++;
                connect(userId);
            }
        };
        
        handler.postDelayed(reconnectRunnable, delay);
    }
    
    /**
     * 取消重连
     */
    private void cancelReconnect() {
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }
    
    /**
     * 处理收到的消息
     */
    private void handleMessage(String text) {
        try {
            JSONObject jsonObject = new JSONObject(text);
            String type = jsonObject.optString("type", "");
            
            ServerMessage message;
            switch (type) {
                case "pong":
                    message = JsonUtils.fromJson(text, PongMessage.class);
                    break;
                case "request_control":
                    message = JsonUtils.fromJson(text, ControlRequestMessage.class);
                    break;
                case "action":
                    message = JsonUtils.fromJson(text, ActionCommandMessage.class);
                    break;
                case "task_end":
                    message = JsonUtils.fromJson(text, TaskEndMessage.class);
                    break;
                case "task_metrics":
                    message = JsonUtils.fromJson(text, TaskMetricsMessage.class);
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
