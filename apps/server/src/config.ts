import dotenv from 'dotenv';
import path from 'path';

// 加载环境变量
dotenv.config({ path: path.join(__dirname, '../.env') });

export interface ServerConfig {
  // Server
  port: number;
  host: string;
  
  // WebSocket
  heartbeatInterval: number;  // 心跳检测间隔 (ms)
  heartbeatTimeout: number;   // 心跳超时时间 (ms)
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
  
  // Task
  authTimeout: number;        // 授权超时时间 (ms)
  taskTimeout: number;        // 任务超时时间 (ms)
  pauseTimeout: number;       // 暂停超时时间 (ms)
  maxSteps: number;           // 最大步骤数
  
  // Feishu Notification
  feishuWebhook?: string;     // Feishu 机器人 Webhook
  feishuAppId?: string;       // Feishu App ID (可选)
  feishuAppSecret?: string;   // Feishu App Secret (可选)
  
  // Storage
  dbPath: string;             // SQLite 数据库路径
  
  // Logging
  logLevel: 'debug' | 'info' | 'warn' | 'error';
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
  
  // WebSocket - 根据设计文档：3分钟心跳
  heartbeatInterval: getEnvNumber('WS_HEARTBEAT_INTERVAL', 3 * 60 * 1000), // 3分钟
  heartbeatTimeout: getEnvNumber('WS_HEARTBEAT_TIMEOUT', 10 * 60 * 1000),   // 10分钟超时
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
  
  // Task - 根据设计文档
  authTimeout: getEnvNumber('AUTH_TIMEOUT', 60 * 1000),      // 60秒授权超时
  taskTimeout: getEnvNumber('TASK_TIMEOUT', 30 * 60 * 1000), // 30分钟任务超时
  pauseTimeout: getEnvNumber('PAUSE_TIMEOUT', 5 * 60 * 1000), // 5分钟暂停超时
  maxSteps: getEnvNumber('MAX_STEPS', 50),                   // 最大50步
  
  // Feishu Notification
  feishuWebhook: process.env.FEISHU_WEBHOOK,
  feishuAppId: process.env.FEISHU_APP_ID,
  feishuAppSecret: process.env.FEISHU_APP_SECRET,
  
  // Storage
  dbPath: getEnv('DB_PATH', './data/ghosttap.db'),
  
  // Logging
  logLevel: (process.env.LOG_LEVEL as ServerConfig['logLevel']) || 'info',
};

// 配置验证
export function validateConfig(): void {
  if (!config.aiApiKey) {
    throw new Error('AI_API_KEY is required');
  }
  
  console.log('[Config] Server config loaded:');
  console.log(`  - Port: ${config.port}`);
  console.log(`  - AI Model: ${config.aiModel}`);
  console.log(`  - AI Retry: ${config.aiRetryMaxAttempts} attempts`);
  console.log(`  - Heartbeat Interval: ${config.heartbeatInterval}ms`);
  console.log(`  - DB Path: ${config.dbPath}`);
  console.log(`  - Feishu Webhook: ${config.feishuWebhook ? 'Configured' : 'Not configured'}`);
}