import dotenv from 'dotenv';
import path from 'path';

// 加载环境变量
dotenv.config({ path: path.join(__dirname, '../.env') });

export interface ServerConfig {
  // Server
  port: number;
  host: string;
  
  // WebSocket - v3.12: 心跳间隔改为90秒
  heartbeatInterval: number;  // 心跳检测间隔 (ms) - 90秒
  heartbeatTimeout: number;   // 心跳超时时间 (ms) - 3分钟（2个心跳周期）
  maxReconnectDelay: number;  // 最大重连延迟 (ms)
  
  // AI
  aiModel: string;
  aiApiKey?: string;
  aiApiUrl?: string;
  aiMaxTokens: number;
  aiTemperature: number;
  
  // AI Retry
  aiRetryMaxAttempts: number;
  aiRetryBaseDelay: number;
  aiRetryMaxDelay: number;
  aiRetryBackoffMultiplier: number;
  
  // Task - v3.12: 移除 authTimeout（不再需要授权等待）
  taskTimeout: number;        // 任务超时时间 (ms) - 30分钟
  pauseTimeout: number;       // 暂停超时时间 (ms) - 5分钟
  maxSteps: number;           // 最大步骤数
  disconnectGrace: number;    // 断连宽限期 (ms) - 30秒
  
  // Feishu Notification (可选，向后兼容)
  feishuWebhook?: string;     // Feishu 机器人 Webhook
  
  // Storage
  dbPath: string;             // SQLite 数据库路径
  
  // Logging
  logLevel: 'debug' | 'info' | 'warn' | 'error';
  
  // Security - MVP 白名单
  allowedUserIds: Set<string>; // 允许的 user_id 白名单
}

function getEnv(key: string, defaultValue?: string): string {
  const value = process.env[key] || defaultValue;
  if (value === undefined) {
    throw new Error(`Missing required environment variable: ${key}`);
  }
  return value;
}

function getEnvNumber(key: string, defaultValue: number): number {
  const value = process.env[key];
  if (value === undefined) {
    return defaultValue;
  }
  const num = parseInt(value, 10);
  if (isNaN(num)) {
    throw new Error(`Environment variable ${key} must be a number`);
  }
  return num;
}

export const config: ServerConfig = {
  // Server
  port: getEnvNumber('PORT', 8080),
  host: getEnv('HOST', '0.0.0.0'),
  
  // WebSocket - v3.12: 心跳间隔90秒（适配NAT超时低至60-120秒的运营商）
  heartbeatInterval: getEnvNumber('WS_HEARTBEAT_INTERVAL', 90 * 1000), // 90秒
  heartbeatTimeout: getEnvNumber('WS_HEARTBEAT_TIMEOUT', 3 * 60 * 1000),   // 3分钟超时（2个心跳周期）
  maxReconnectDelay: getEnvNumber('WS_MAX_RECONNECT_DELAY', 60 * 1000),     // 60秒最大重连
  
  // AI - 使用用户当前的模型 kimi-coding/k2p5
  aiModel: getEnv('AI_MODEL', 'kimi-coding/k2p5'),
  aiApiKey: process.env.AI_API_KEY,
  aiApiUrl: process.env.AI_API_URL,
  aiMaxTokens: getEnvNumber('AI_MAX_TOKENS', 2000),
  aiTemperature: getEnvNumber('AI_TEMPERATURE', 0.3),
  
  // AI Retry - 重试机制配置
  aiRetryMaxAttempts: getEnvNumber('AI_RETRY_MAX_ATTEMPTS', 3),
  aiRetryBaseDelay: getEnvNumber('AI_RETRY_BASE_DELAY', 1000),
  aiRetryMaxDelay: getEnvNumber('AI_RETRY_MAX_DELAY', 10000),
  aiRetryBackoffMultiplier: getEnvNumber('AI_RETRY_BACKOFF_MULTIPLIER', 2),
  
  // Task - v3.12: 移除 authTimeout，任务创建后直接 running
  taskTimeout: getEnvNumber('TASK_TIMEOUT', 30 * 60 * 1000), // 30分钟任务超时
  pauseTimeout: getEnvNumber('PAUSE_TIMEOUT', 5 * 60 * 1000), // 5分钟暂停超时
  maxSteps: getEnvNumber('MAX_STEPS', 50),                   // 最大50步
  disconnectGrace: getEnvNumber('DISCONNECT_GRACE', 30 * 1000), // 30秒断连宽限期
  
  // Feishu Notification (可选，向后兼容)
  feishuWebhook: process.env.FEISHU_WEBHOOK,
  
  // Storage
  dbPath: getEnv('DB_PATH', './data/ghosttap.db'),
  
  // Logging
  logLevel: (process.env.LOG_LEVEL as ServerConfig['logLevel']) || 'info',
  
  // Security - MVP 白名单（逗号分隔的 user_id 列表）
  allowedUserIds: new Set(
    (process.env.ALLOWED_USER_IDS || 'a399bea4dba7').split(',').map(id => id.trim()).filter(Boolean)
  ),
};

// 配置验证
export function validateConfig(): void {
  if (!config.aiApiKey) {
    throw new Error('AI_API_KEY is required');
  }
  
  if (config.allowedUserIds.size === 0) {
    throw new Error('ALLOWED_USER_IDS is required (comma-separated user IDs)');
  }
  
  console.log('[Config] Server config loaded:');
  console.log(`  - Port: ${config.port}`);
  console.log(`  - AI Model: ${config.aiModel}`);
  console.log(`  - AI Retry: ${config.aiRetryMaxAttempts} attempts`);
  console.log(`  - Heartbeat Interval: ${config.heartbeatInterval}ms (90s)`);
  console.log(`  - DB Path: ${config.dbPath}`);
  console.log(`  - Allowed User IDs: ${Array.from(config.allowedUserIds).join(', ')}`);
}