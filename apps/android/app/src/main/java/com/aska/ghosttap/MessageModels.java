package com.aska.ghosttap;

import java.util.List;

/**
 * GhostTap 通信协议定义 (v3.12)
 * 用于 Android 客户端和 Server 之间的数据交换
 */

// ============ 屏幕信息 ============

/**
 * 屏幕信息
 */
class ScreenInfo {
    public int width;
    public int height;
    public String orientation; // "portrait" or "landscape"
    public boolean keyboard_visible;  // 软键盘是否可见
    public float keyboard_height;     // 软键盘占屏幕高度百分比（0-100）
    
    public ScreenInfo() {}
    
    public ScreenInfo(int width, int height, String orientation) {
        this.width = width;
        this.height = height;
        this.orientation = orientation;
        this.keyboard_visible = false;
        this.keyboard_height = 0f;
    }
    
    public ScreenInfo(int width, int height, String orientation, 
                      boolean keyboard_visible, float keyboard_height) {
        this.width = width;
        this.height = height;
        this.orientation = orientation;
        this.keyboard_visible = keyboard_visible;
        this.keyboard_height = keyboard_height;
    }
}

/**
 * UI 元素
 */
class UiElement {
    public int id;
    public String type; // "input", "btn", "text", "icon", "image", "other"
    public String text;
    public String desc;
    public List<Float> pos; // [x1%, y1%, x2%, y2%]
    public List<Float> center; // [x%, y%]
    public List<String> actions; // ["click", "input"]
    
    public UiElement() {}
    
    public UiElement(int id, String type, String text, String desc, 
                     List<Float> pos, List<Float> center, List<String> actions) {
        this.id = id;
        this.type = type;
        this.text = text;
        this.desc = desc;
        this.pos = pos;
        this.center = center;
        this.actions = actions;
    }
}

/**
 * UI 统计信息
 */
class UiStats {
    public int original_nodes;
    public int filtered_nodes;
    public boolean truncated;  // 是否被截断（超过最大元素数）
    
    public UiStats() {
        this.truncated = false;
    }
    
    public UiStats(int original_nodes, int filtered_nodes) {
        this.original_nodes = original_nodes;
        this.filtered_nodes = filtered_nodes;
        this.truncated = false;
    }
    
    public UiStats(int original_nodes, int filtered_nodes, boolean truncated) {
        this.original_nodes = original_nodes;
        this.filtered_nodes = filtered_nodes;
        this.truncated = truncated;
    }
}

// ============ 上行消息（手机 → 云端）============

/**
 * 客户端消息基类
 */
abstract class ClientMessage {
    public String type;
}

/**
 * UI 事件消息
 */
class UiEventMessage extends ClientMessage {
    public long timestamp;
    public String session_id;
    public String package_name;
    public String activity;
    public ScreenInfo screen;
    public List<UiElement> elements;
    public UiStats stats;
    
    public UiEventMessage() {
        this.type = "ui_event";
    }
    
    public UiEventMessage(long timestamp, String session_id, 
                          String package_name, String activity, ScreenInfo screen,
                          List<UiElement> elements, UiStats stats) {
        this.type = "ui_event";
        this.timestamp = timestamp;
        this.session_id = session_id;
        this.package_name = package_name;
        this.activity = activity;
        this.screen = screen;
        this.elements = elements;
        this.stats = stats;
    }
}

/**
 * Ping 消息（v3.12: 移除 user_id）
 */
class PingMessage extends ClientMessage {
    public long timestamp;
    
    public PingMessage() {
        this.type = "ping";
    }
    
    public PingMessage(long timestamp) {
        this.type = "ping";
        this.timestamp = timestamp;
    }
}

/**
 * 暂停请求（v3.12: 新增，用户点击悬浮窗暂停按钮）
 */
class PauseRequest extends ClientMessage {
    public String session_id;
    
    public PauseRequest() {
        this.type = "pause";
    }
    
    public PauseRequest(String session_id) {
        this.type = "pause";
        this.session_id = session_id;
    }
}

/**
 * 恢复请求（v3.12: 新增，用户点击悬浮窗继续按钮）
 */
class ResumeRequest extends ClientMessage {
    public String session_id;
    
    public ResumeRequest() {
        this.type = "resume";
    }
    
    public ResumeRequest(String session_id) {
        this.type = "resume";
        this.session_id = session_id;
    }
}

/**
 * 停止请求（v3.12: 新增，用户点击悬浮窗结束按钮）
 */
class StopRequest extends ClientMessage {
    public String session_id;
    
    public StopRequest() {
        this.type = "stop";
    }
    
    public StopRequest(String session_id) {
        this.type = "stop";
        this.session_id = session_id;
    }
}

/**
 * 错误事件（v3.12: 新增，动作执行失败时上报）
 */
class ErrorEvent extends ClientMessage {
    public String session_id;
    public String error;      // 错误码（如 "PACKAGE_NOT_FOUND"）
    public String message;    // 可读描述
    
    public ErrorEvent() {
        this.type = "error";
    }
    
    public ErrorEvent(String session_id, String error, String message) {
        this.type = "error";
        this.session_id = session_id;
        this.error = error;
        this.message = message;
    }
}

// ============ 下行消息（云端 → 手机）============

/**
 * 服务端消息基类
 */
abstract class ServerMessage {
    public String type;
}

/**
 * Pong 消息（v3.12: 移除 server_time，只回显 timestamp）
 */
class PongMessage extends ServerMessage {
    public long timestamp;  // 回显 Ping 的发送时间戳
    
    public PongMessage() {
        this.type = "pong";
    }
}

/**
 * 任务开始（v3.12: 新增，替代 ControlRequest）
 * 云端收到任务创建请求后直接下发，手机立即开始上报 UI
 */
class TaskStart extends ServerMessage {
    public String session_id;
    public String goal;  // 任务目标
    
    public TaskStart() {
        this.type = "task_start";
    }
}

/**
 * 任务恢复（v3.12: 新增，断连重连时下发）
 */
class TaskResume extends ServerMessage {
    public String session_id;
    public String goal;           // 当前任务目标
    public String status;         // "running" 或 "paused"
    public String reason;         // paused 时的暂停原因
    
    public TaskResume() {
        this.type = "task_resume";
    }
}

/**
 * 动作目标（v3.12: 只保留 center，移除 element_id）
 */
class ActionTarget {
    public List<Float> center;  // [x%, y%] - 手机端执行的唯一定位依据
    
    public ActionTarget() {}
    
    public ActionTarget(List<Float> center) {
        this.center = center;
    }
}

/**
 * 动作命令消息（v3.12: 更新字段）
 */
class ActionCommandMessage extends ServerMessage {
    public String action;        // "click", "input", "swipe", "back", "home", 
                                 // "launch_app", "wait", "pause"
    public ActionTarget target;  // click/input 动作时必填
    public String text;          // input 动作时的输入文本
    public String direction;     // swipe 动作方向: "up", "down", "left", "right"
    public Float distance;       // swipe 滑动距离（屏幕百分比，默认30）
    public Integer duration_ms;  // swipe 滑动时长（毫秒，默认300）
    public String package_name;  // launch_app 动作时的目标APP包名
    public Integer wait_ms;      // wait 动作的等待毫秒数
    public String reason;        // pause 的说明或其他原因
    
    public ActionCommandMessage() {
        this.type = "action";
    }
}

/**
 * 任务结束（v3.12: 唯一的任务终止消息）
 */
class TaskEnd extends ServerMessage {
    public String session_id;
    public String status;   // "success", "failed", "cancelled"
    public String result;   // 结果说明
    
    public TaskEnd() {
        this.type = "task_end";
    }
}

// v3.12 移除的消息类型（不再使用）：
// - ControlRequestMessage (改为 TaskStart)
// - AuthorizationMessage (不再需要授权)
// - TaskMetricsMessage (不再下发给手机)
// - ResumeMessage (服务端下行改为客户端上行)
