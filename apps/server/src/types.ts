/**
 * GhostTap 通信协议定义
 * 用于 Android 客户端和 Server 之间的数据交换
 */

// ============ 基础类型 ============

export interface ScreenInfo {
  width: number;
  height: number;
  orientation: 'portrait' | 'landscape';
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
}

// ============ 上行消息（手机 → 云端）============

export interface PingMessage {
  type: 'ping';
  user_id: string;
  timestamp: number;
}

export interface UiEventMessage {
  type: 'ui_event';
  timestamp: number;
  user_id: string;
  session_id: string;
  package: string;
  activity: string;
  screen: ScreenInfo;
  elements: Element[];
  stats?: UiStats;
}

export interface AuthorizationMessage {
  type: 'authorization';
  user_id: string;
  session_id: string;
  decision: 'allowed' | 'denied';
}

export interface ResumeMessage {
  type: 'resume';
  user_id: string;
  session_id: string;
}

export type ClientMessage = 
  | PingMessage 
  | UiEventMessage 
  | AuthorizationMessage 
  | ResumeMessage;

// ============ 下行消息（云端 → 手机）============

export interface PongMessage {
  type: 'pong';
  timestamp: number;
  server_time: number;
}

export interface ControlRequestMessage {
  type: 'request_control';
  user_id: string;
  session_id: string;
  goal: string;
  timeout_ms: number;
}

export interface ActionTarget {
  element_id?: number;
  center: [number, number]; // [x%, y%]
}

export interface ActionCommandMessage {
  type: 'action';
  action: 'click' | 'input' | 'swipe' | 'back' | 'wait' | 'pause' | 'done' | 'fail';
  target?: ActionTarget;
  text?: string;
  direction?: 'up' | 'down' | 'left' | 'right';
  reason?: string;
  expect?: string;
  ms?: number; // for wait action
}

export interface TaskEndMessage {
  type: 'task_end';
  session_id: string;
  status: 'success' | 'failed' | 'cancelled';
  result?: string;
}

export interface TaskMetricsMessage {
  type: 'task_metrics';
  session_id: string;
  current_step: number;
  total_steps?: number;
  current_cost: number;
  total_tokens: number;
  input_tokens: number;
  output_tokens: number;
  model: string;
}

export type ServerMessage = 
  | PongMessage 
  | ControlRequestMessage 
  | ActionCommandMessage 
  | TaskEndMessage 
  | TaskMetricsMessage;

// ============ AI 相关类型 ============

export interface AiDecision {
  thought: string;
  action: string;
  target?: {
    element_id?: number;
    center: [number, number];
  };
  text?: string;
  reason: string;
  expect: string;
  params?: Record<string, any>;
}

// ============ 会话状态 ============

export type TaskStatus = 
  | 'pending'      // 等待用户授权
  | 'running'      // 正在执行
  | 'paused'       // 暂停等待用户
  | 'completed'    // 已完成
  | 'failed'       // 失败
  | 'cancelled';   // 被取消

export interface TaskSession {
  session_id: string;
  user_id: string;
  goal: string;
  status: TaskStatus;
  device_ws?: WebSocket;  // 手机端的WebSocket连接
  callback_url?: string;  // OpenClaw Skill 回调地址
  created_at: number;
  updated_at: number;
  last_ui_event?: UiEventMessage;
  history: ActionRecord[];
  metrics: TaskMetrics;
  result?: string;  // 任务结果描述
}

// ============ OpenClaw Skill 回调消息类型 ============

export type CallbackMessageType = 
  | 'task_completed' 
  | 'auth_request' 
  | 'auth_result' 
  | 'device_connected' 
  | 'device_disconnected';

export interface BaseCallbackMessage {
  type: CallbackMessageType;
  user_id: string;
  timestamp: number;
}

export interface TaskCompletedMessage extends BaseCallbackMessage {
  type: 'task_completed';
  session_id: string;
  status: 'completed' | 'failed' | 'cancelled';
  result: string;
  goal: string;
  steps: number;
  cost_usd: number;
}

export interface AuthRequestMessage extends BaseCallbackMessage {
  type: 'auth_request';
  session_id: string;
  goal: string;
  timeout_sec: number;
}

export interface AuthResultMessage extends BaseCallbackMessage {
  type: 'auth_result';
  session_id: string;
  decision: 'allowed' | 'denied';
  goal: string;
}

export interface DeviceStatusMessage extends BaseCallbackMessage {
  type: 'device_connected' | 'device_disconnected';
  device_name?: string;
}

export type CallbackMessage = 
  | TaskCompletedMessage 
  | AuthRequestMessage 
  | AuthResultMessage 
  | DeviceStatusMessage;

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