import sqlite3 from 'sqlite3';
import { open, Database } from 'sqlite';
import path from 'path';
import fs from 'fs';
import { config } from './config';
import { logger } from './logger';
import { TaskMetrics } from './types';

/**
 * SQLite 数据库管理器
 * 简单稳定的本地存储
 */
export class DatabaseManager {
  private db: Database<sqlite3.Database, sqlite3.Statement> | null = null;

  constructor() {
    this.init();
  }

  /**
   * 初始化数据库
   */
  private async init(): Promise<void> {
    try {
      // 确保数据目录存在
      const dbDir = path.dirname(config.dbPath);
      if (!fs.existsSync(dbDir)) {
        fs.mkdirSync(dbDir, { recursive: true });
      }

      this.db = await open({
        filename: config.dbPath,
        driver: sqlite3.Database,
      });
      
      // 创建表
      await this.createTables();
      
      logger.info('Database initialized', { path: config.dbPath });
    } catch (error) {
      logger.error('Failed to initialize database', error);
      throw error;
    }
  }

  /**
   * 创建数据表
   */
  private async createTables(): Promise<void> {
    if (!this.db) return;

    // 会话表
    await this.db.exec(`
      CREATE TABLE IF NOT EXISTS sessions (
        session_id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL,
        goal TEXT NOT NULL,
        status TEXT NOT NULL,
        callback_url TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        result TEXT,
        total_tokens INTEGER DEFAULT 0,
        input_tokens INTEGER DEFAULT 0,
        output_tokens INTEGER DEFAULT 0,
        cost_usd REAL DEFAULT 0,
        model TEXT,
        step_count INTEGER DEFAULT 0
      )
    `);

    // 创建索引
    await this.db.exec(`
      CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
      CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);
      CREATE INDEX IF NOT EXISTS idx_sessions_created ON sessions(created_at);
    `);

    // 动作历史表
    await this.db.exec(`
      CREATE TABLE IF NOT EXISTS action_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        session_id TEXT NOT NULL,
        step INTEGER NOT NULL,
        timestamp INTEGER NOT NULL,
        action TEXT NOT NULL,
        reason TEXT,
        result TEXT,
        FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
      )
    `);

    // 设备绑定表（单设备绑定）
    await this.db.exec(`
      CREATE TABLE IF NOT EXISTS device_bindings (
        user_id TEXT PRIMARY KEY,
        device_name TEXT,
        connected_at INTEGER NOT NULL,
        last_ping INTEGER NOT NULL
      )
    `);
  }

  /**
   * 保存会话
   */
  async saveSession(data: {
    session_id: string;
    user_id: string;
    goal: string;
    status: string;
    callback_url?: string;
    created_at: number;
    updated_at: number;
    result?: string;
    metrics?: TaskMetrics;
  }): Promise<void> {
    if (!this.db) return;

    await this.db.run(
      `INSERT OR REPLACE INTO sessions 
      (session_id, user_id, goal, status, callback_url, created_at, updated_at, result,
       total_tokens, input_tokens, output_tokens, cost_usd, model, step_count)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      data.session_id,
      data.user_id,
      data.goal,
      data.status,
      data.callback_url || null,
      data.created_at,
      data.updated_at,
      data.result || null,
      data.metrics?.total_tokens || 0,
      data.metrics?.input_tokens || 0,
      data.metrics?.output_tokens || 0,
      data.metrics?.cost_usd || 0,
      data.metrics?.model || null,
      data.metrics?.step_count || 0
    );
  }

  /**
   * 更新会话状态
   */
  async updateSessionStatus(sessionId: string, status: string, result?: string): Promise<void> {
    if (!this.db) return;

    await this.db.run(
      `UPDATE sessions 
      SET status = ?, updated_at = ?, result = COALESCE(?, result)
      WHERE session_id = ?`,
      status,
      Date.now(),
      result || null,
      sessionId
    );
  }

  /**
   * 更新会话指标
   */
  async updateSessionMetrics(sessionId: string, metrics: TaskMetrics): Promise<void> {
    if (!this.db) return;

    await this.db.run(
      `UPDATE sessions 
      SET total_tokens = ?, input_tokens = ?, output_tokens = ?, 
          cost_usd = ?, model = ?, step_count = ?, updated_at = ?
      WHERE session_id = ?`,
      metrics.total_tokens,
      metrics.input_tokens,
      metrics.output_tokens,
      metrics.cost_usd,
      metrics.model,
      metrics.step_count,
      Date.now(),
      sessionId
    );
  }

  /**
   * 保存动作记录
   */
  async saveActionRecord(sessionId: string, step: number, action: string, reason: string, result?: string): Promise<void> {
    if (!this.db) return;

    await this.db.run(
      `INSERT INTO action_history (session_id, step, timestamp, action, reason, result)
      VALUES (?, ?, ?, ?, ?, ?)`,
      sessionId,
      step,
      Date.now(),
      action,
      reason,
      result || null
    );
  }

  /**
   * 获取会话
   */
  async getSession(sessionId: string): Promise<any> {
    if (!this.db) return null;

    return this.db.get('SELECT * FROM sessions WHERE session_id = ?', sessionId);
  }

  /**
   * 更新会话的回调地址
   */
  async updateSessionCallbackUrl(sessionId: string, callbackUrl: string): Promise<void> {
    if (!this.db) return;

    await this.db.run(
      `UPDATE sessions SET callback_url = ?, updated_at = ? WHERE session_id = ?`,
      callbackUrl,
      Date.now(),
      sessionId
    );
  }

  /**
   * 获取用户的活跃会话
   */
  async getUserActiveSession(userId: string): Promise<any> {
    if (!this.db) return null;

    return this.db.get(
      `SELECT * FROM sessions 
      WHERE user_id = ? AND status IN ('pending', 'running', 'paused')
      ORDER BY created_at DESC LIMIT 1`,
      userId
    );
  }

  /**
   * 获取用户的历史会话
   */
  async getUserSessions(userId: string, limit: number = 10): Promise<any[]> {
    if (!this.db) return [];

    return this.db.all(
      `SELECT * FROM sessions 
      WHERE user_id = ?
      ORDER BY created_at DESC LIMIT ?`,
      userId,
      limit
    );
  }

  /**
   * 获取会话历史动作
   */
  async getSessionHistory(sessionId: string): Promise<any[]> {
    if (!this.db) return [];

    return this.db.all(
      `SELECT * FROM action_history 
      WHERE session_id = ?
      ORDER BY step ASC`,
      sessionId
    );
  }

  /**
   * 更新设备绑定（单设备绑定）
   * v3.12: 添加 device_name 字段
   */
  async updateDeviceBinding(userId: string, deviceName?: string): Promise<void> {
    if (!this.db) return;

    const now = Date.now();
    await this.db.run(
      `INSERT OR REPLACE INTO device_bindings (user_id, device_name, connected_at, last_ping)
      VALUES (?, COALESCE(?, (SELECT device_name FROM device_bindings WHERE user_id = ?)), 
              COALESCE((SELECT connected_at FROM device_bindings WHERE user_id = ?), ?), ?)`,
      userId,
      deviceName || null,
      userId,
      userId,
      now,
      now
    );
  }

  /**
   * 移除设备绑定
   */
  async removeDeviceBinding(userId: string): Promise<void> {
    if (!this.db) return;

    await this.db.run('DELETE FROM device_bindings WHERE user_id = ?', userId);
  }

  /**
   * 获取设备绑定
   */
  async getDeviceBinding(userId: string): Promise<any> {
    if (!this.db) return null;

    return this.db.get('SELECT * FROM device_bindings WHERE user_id = ?', userId);
  }

  /**
   * 关闭数据库
   */
  async close(): Promise<void> {
    if (this.db) {
      await this.db.close();
      this.db = null;
    }
  }
}

// 导出单例
export const dbManager = new DatabaseManager();