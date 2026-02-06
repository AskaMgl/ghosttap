/**
 * GhostTap Protocol - 共享协议定义
 * 用于 Android 客户端和 Server 之间的数据交换
 * 
 * @version 1.0.0
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
  pos: [number, number, number, number]; // [x1%, y1%, x2%, y2%] 百分比坐标
  center: [number, number]; // [x%, y%] 百分比坐标
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
  center: [number, number]; // [x%, y%] 百分比坐标
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

// ============ 协议常量 ============

export const PROTOCOL_VERSION = '1.0.0';

// 元素类型映射
export const ElementTypeMap = {
  EDIT_TEXT: 'input',
  BUTTON: 'btn',
  TEXT_VIEW: 'text',
  IMAGE_VIEW: 'image',
  IMAGE: 'image',
  ICON: 'icon',
} as const;

// 默认心跳间隔（3分钟）
export const DEFAULT_HEARTBEAT_INTERVAL = 3 * 60 * 1000;

// 默认心跳超时（10分钟）
export const DEFAULT_HEARTBEAT_TIMEOUT = 10 * 60 * 1000;