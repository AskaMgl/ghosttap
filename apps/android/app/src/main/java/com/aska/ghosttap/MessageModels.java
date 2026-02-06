package com.aska.ghosttap;

import java.util.List;

/**
 * UI 事件消息
 */
public class UiEventMessage {
    public String type = "ui_event";
    public long timestamp;
    public String user_id;
    public String session_id;
    public String package_name;
    public String activity;
    public ScreenInfo screen;
    public List<UiElement> elements;
    public UiStats stats;
    
    public UiEventMessage() {}
    
    public UiEventMessage(long timestamp, String user_id, String session_id, 
                          String package_name, String activity, ScreenInfo screen,
                          List<UiElement> elements, UiStats stats) {
        this.timestamp = timestamp;
        this.user_id = user_id;
        this.session_id = session_id;
        this.package_name = package_name;
        this.activity = activity;
        this.screen = screen;
        this.elements = elements;
        this.stats = stats;
    }
}

/**
 * 屏幕信息
 */
class ScreenInfo {
    public int width;
    public int height;
    public String orientation; // "portrait" or "landscape"
    
    public ScreenInfo() {}
    
    public ScreenInfo(int width, int height, String orientation) {
        this.width = width;
        this.height = height;
        this.orientation = orientation;
    }
}

/**
 * UI 元素
 */
class UiElement {
    public int id;
    public String type; // "input", "btn", "text", "icon", "image", "scroll", "other"
    public String text;
    public String desc;
    public List<Float> pos; // [x1%, y1%, x2%, y2%]
    public List<Float> center; // [x%, y%]
    public List<String> actions; // ["click", "input", "swipe"]
    
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
    
    public UiStats() {}
    
    public UiStats(int original_nodes, int filtered_nodes) {
        this.original_nodes = original_nodes;
        this.filtered_nodes = filtered_nodes;
    }
}

/**
 * 客户端消息基类
 */
abstract class ClientMessage {
    public String type;
}

/**
 * Ping 消息
 */
class PingMessage extends ClientMessage {
    public String user_id;
    public long timestamp;
    
    public PingMessage() {
        this.type = "ping";
    }
    
    public PingMessage(String user_id, long timestamp) {
        this.type = "ping";
        this.user_id = user_id;
        this.timestamp = timestamp;
    }
}

/**
 * 授权消息
 */
class AuthorizationMessage extends ClientMessage {
    public String user_id;
    public String session_id;
    public String decision; // "allowed" or "denied"
    
    public AuthorizationMessage() {
        this.type = "authorization";
    }
    
    public AuthorizationMessage(String user_id, String session_id, String decision) {
        this.type = "authorization";
        this.user_id = user_id;
        this.session_id = session_id;
        this.decision = decision;
    }
}

/**
 * 恢复任务消息
 */
class ResumeMessage extends ClientMessage {
    public String user_id;
    public String session_id;
    
    public ResumeMessage() {
        this.type = "resume";
    }
    
    public ResumeMessage(String user_id, String session_id) {
        this.type = "resume";
        this.user_id = user_id;
        this.session_id = session_id;
    }
}

/**
 * 服务端消息基类
 */
abstract class ServerMessage {
    public String type;
}

/**
 * Pong 消息
 */
class PongMessage extends ServerMessage {
    public long timestamp;
    public long server_time;
    
    public PongMessage() {
        this.type = "pong";
    }
}

/**
 * 控制请求消息
 */
class ControlRequestMessage extends ServerMessage {
    public String user_id;
    public String session_id;
    public String goal;
    public int timeout_ms;
    
    public ControlRequestMessage() {
        this.type = "request_control";
    }
}

/**
 * 动作目标
 */
class ActionTarget {
    public Integer element_id;
    public List<Float> center;
    
    public ActionTarget() {}
    
    public ActionTarget(Integer element_id, List<Float> center) {
        this.element_id = element_id;
        this.center = center;
    }
}

/**
 * 动作命令消息
 */
class ActionCommandMessage extends ServerMessage {
    public String action;
    public ActionTarget target;
    public String text;
    public String direction;
    public String reason;
    public String expect;
    public Integer ms;
    
    public ActionCommandMessage() {
        this.type = "action";
    }
}

/**
 * 任务结束消息
 */
class TaskEndMessage extends ServerMessage {
    public String session_id;
    public String status;
    public String result;
    
    public TaskEndMessage() {
        this.type = "task_end";
    }
}

/**
 * 任务指标消息
 */
class TaskMetricsMessage extends ServerMessage {
    public String session_id;
    public int current_step;
    public Integer total_steps;
    public double current_cost;
    public int total_tokens;
    public int input_tokens;
    public int output_tokens;
    public String model;
    
    public TaskMetricsMessage() {
        this.type = "task_metrics";
    }
}
