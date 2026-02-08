/**
 * GhostTap 通信协议定义 (v3.12)
 * 用于 Android 客户端和 Server 之间的数据交换
 */

// ============ 基础类型 ============

export interface ScreenInfo {
  width: number;
  height: number;
  orientation: 'portrait' | 'landscape';
  keyboard_visible: boolean;  // 软键盘是否可见
  keyboard_height: number;    // 软键盘占屏幕高度百分比（0-100）
}

export interface Element {
  id: number;
  type: 'input' | 'btn' | 'text' | 'icon' | 'image' | 'other';
  text?: string;
  desc?: string;
  pos: [number, number, number, number]; // [x1%, y1%, x2%, y2%]
  center: [number, number]; // [x%, y%]
  actions: string[];
}

export interface UiStats {
  original_nodes: number;
  filtered_nodes: number;
  truncated: boolean;
}

// ============ 上行消息（手机 → 云端）============

export interface PingMessage {
  type: 'ping';
  timestamp: number;
}

export interface UiEventMessage {
  type: 'ui_event';
  timestamp: number;
  session_id: string;
  package: string;
  activity: string;
  screen: ScreenInfo;
  elements: Element[];
  stats?: UiStats;
}

export interface PauseRequest {
  type: 'pause';
  session_id: string;
}

export interface ResumeRequest {
  type: 'resume';
  session_id: string;
}

export interface StopRequest {
  type: 'stop';
  session_id: string;
}

export interface ErrorEvent {
  type: 'error';
  session_id: string;
  error: string;      // 错误码（如 "PACKAGE_NOT_FOUND"）
  message: string;    // 可读描述
}

export type ClientMessage = 
  | PingMessage 
  | UiEventMessage 
  | PauseRequest
  | ResumeRequest
  | StopRequest
  | ErrorEvent;

// ============ 下行消息（云端 → 手机）============

export interface PongMessage {
  type: 'pong';
  timestamp: number;  // 回显 Ping 的发送时间戳
}

export interface TaskStart {
  type: 'task_start';
  session_id: string;
  goal: string;
}

export interface ActionTarget {
  center: [number, number]; // [x%, y%] - 手机端执行的唯一定位依据
}

export interface ActionCommandMessage {
  type: 'action';
  action: 'click' | 'input' | 'swipe' | 'back' | 'home' | 'launch_app' | 'wait' | 'pause';
  target?: ActionTarget;
  text?: string;              // input 动作时的输入文本
  direction?: 'up' | 'down' | 'left' | 'right';  // swipe 动作方向
  distance?: number;          // swipe 滑动距离（屏幕百分比，默认30）
  duration_ms?: number;       // swipe 滑动时长（毫秒，默认300）
  package_name?: string;      // launch_app 动作时的目标APP包名
  wait_ms?: number;           // wait 动作的等待毫秒数
  reason?: string;            // pause 的说明或其他原因
}

export interface TaskEnd {
  type: 'task_end';
  session_id: string;
  status: 'success' | 'failed' | 'cancelled';
  result?: string;
}

export interface TaskResume {
  type: 'task_resume';
  session_id: string;
  goal: string;              // 当前任务目标
  status: 'running' | 'paused';  // 任务当前状态
  reason?: string;           // paused 时的暂停原因
}

export type ServerMessage = 
  | PongMessage 
  | TaskStart
  | ActionCommandMessage 
  | TaskEnd
  | TaskResume;

// ============ AI 相关类型 ============

export interface AiDecision {
  thought: string;
  action: string;
  target?: {
    center: [number, number];  // 百分比坐标，手机端执行依据
  };
  text?: string;                 // input 动作时的文本
  direction?: 'up' | 'down' | 'left' | 'right';  // swipe 方向
  distance?: number;             // swipe 距离百分比
  duration_ms?: number;          // swipe 时长毫秒
  package_name?: string;         // launch_app 包名
  wait_ms?: number;              // wait 时长毫秒
  reason: string;
  // 注意：v3.12 移除了 expect 和 element_id
}

// ============ 会话状态 ============

export type TaskStatus = 
  | 'running'      // 正在执行
  | 'paused'       // 暂停等待用户
  | 'completed'    // 已完成
  | 'failed'       // 失败
  | 'cancelled';   // 被取消

// SessionContext 用于云端内部管理
export interface SessionContext {
  session_id: string;
  user_id: string;
  goal: string;
  status: TaskStatus;
  device_ws?: WebSocket;
  callback_url?: string;
  created_at: number;
  updated_at: number;
  last_ui_event?: UiEventMessage;
  latestUiTimestamp: number;    // 最新UI事件的时间戳，用于过期检测
  aiCallInFlight: boolean;      // 是否有AI调用正在进行（互斥锁）
  history: ActionRecord[];
  metrics: TaskMetrics;
  result?: string;
  // v3.14: 断连宽限期相关
  deviceDisconnectedAt?: number;  // 设备断开连接的时间戳
  isWaitingReconnect?: boolean;   // 是否在等待设备重连
}

// ============ OpenClaw Skill 回调消息类型 ============

// v3.12 简化：只保留 task_completed 回调
export interface TaskCompletedCallback {
  type: 'task_completed';
  user_id: string;
  session_id: string;
  status: 'completed' | 'failed' | 'cancelled';
  result: string;
  goal: string;
  steps: number;
  cost_usd: number;
  timestamp: number;
}

export type CallbackMessage = TaskCompletedCallback;

export interface ActionRecord {
  step: number;
  timestamp: number;
  action: string;
  reason: string;
  result?: string;
}

export interface TaskMetrics {
  total_tokens: number;
  input_tokens: number;
  output_tokens: number;
  cost_usd: number;
  model: string;
  step_count: number;
}

// ============ 数据库持久化类型 ============

export interface DbSession {
  session_id: string;
  user_id: string;
  goal: string;
  status: TaskStatus;
  callback_url?: string;
  created_at: number;
  updated_at: number;
  result?: string;
  total_tokens: number;
  input_tokens: number;
  output_tokens: number;
  cost_usd: number;
  model: string;
  step_count: number;
}

export interface DbActionHistory {
  id: number;
  session_id: string;
  step: number;
  timestamp: number;
  action: string;
  reason: string;
  result?: string;
}

export interface DbDeviceBinding {
  user_id: string;
  device_name?: string;
  connected_at: number;
  last_ping: number;
}

// ============ WebSocket 客户端信息 ============

export interface ClientInfo {
  ws: any;  // WebSocket instance from 'ws' library
  user_id: string;
  device_name: string;
  lastPing: number;
  isAuthenticated: boolean;
}
