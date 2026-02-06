import { TaskSession, TaskStatus, UiEventMessage, ActionRecord, TaskMetrics } from './types';
import { logger } from './logger';
import { config } from './config';
import { dbManager } from './database';

/**
 * 会话管理器 - SQLite 持久化存储
 * 负责管理所有任务会话的生命周期
 */
export class SessionManager {
  private activeSessions: Map<string, TaskSession> = new Map(); // 活跃会话缓存
  private cleanupInterval: NodeJS.Timeout | null = null;

  constructor() {
    // 启动定时清理任务
    this.cleanupInterval = setInterval(() => {
      this.cleanupExpiredSessions();
    }, 60 * 1000); // 每分钟清理一次
  }

  /**
   * 创建新会话
   */
  createSession(userId: string, goal: string, deviceWs?: WebSocket, callbackUrl?: string): TaskSession {
    const sessionId = `sess_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    
    const session: TaskSession = {
      session_id: sessionId,
      user_id: userId,
      goal,
      status: 'pending',
      device_ws: deviceWs,
      callback_url: callbackUrl,
      created_at: Date.now(),
      updated_at: Date.now(),
      history: [],
      metrics: {
        total_tokens: 0,
        input_tokens: 0,
        output_tokens: 0,
        cost_usd: 0,
        model: config.aiModel,
        step_count: 0,
      },
    };

    this.activeSessions.set(sessionId, session);
    
    // 持久化到数据库（异步，不阻塞）
    dbManager.saveSession({
      session_id: sessionId,
      user_id: userId,
      goal,
      status: 'pending',
      callback_url: callbackUrl,
      created_at: session.created_at,
      updated_at: session.updated_at,
    }).catch(err => logger.error('Failed to save session', err));

    logger.info('Session created', { 
      session_id: sessionId, 
      user_id: userId, 
      goal,
      callback_url: callbackUrl,
      total_sessions: this.activeSessions.size 
    });

    return session;
  }

  /**
   * 更新会话的回调地址
   */
  updateSessionCallbackUrl(sessionId: string, callbackUrl: string): TaskSession | undefined {
    const session = this.activeSessions.get(sessionId);
    if (!session) return undefined;

    session.callback_url = callbackUrl;
    session.updated_at = Date.now();

    // 持久化到数据库（异步）
    dbManager.updateSessionCallbackUrl(sessionId, callbackUrl).catch(err => 
      logger.error('Failed to update session callback URL', err)
    );

    logger.info('Session callback URL updated', { 
      session_id: sessionId, 
      callback_url: callbackUrl 
    });

    return session;
  }

  /**
   * 获取会话
   */
  async getSession(sessionId: string): Promise<TaskSession | undefined> {
    // 先从缓存获取
    if (this.activeSessions.has(sessionId)) {
      return this.activeSessions.get(sessionId);
    }
    
    // 缓存未命中，从数据库加载
    const dbSession = await dbManager.getSession(sessionId);
    if (dbSession) {
      const history = await dbManager.getSessionHistory(sessionId);
      const session: TaskSession = {
        session_id: dbSession.session_id,
        user_id: dbSession.user_id,
        goal: dbSession.goal,
        status: dbSession.status,
        callback_url: dbSession.callback_url,
        created_at: dbSession.created_at,
        updated_at: dbSession.updated_at,
        result: dbSession.result,
        history: history.map((h: any) => ({
          step: h.step,
          timestamp: h.timestamp,
          action: h.action,
          reason: h.reason,
          result: h.result,
        })),
        metrics: {
          total_tokens: dbSession.total_tokens,
          input_tokens: dbSession.input_tokens,
          output_tokens: dbSession.output_tokens,
          cost_usd: dbSession.cost_usd,
          model: dbSession.model,
          step_count: dbSession.step_count,
        },
      };
      
      // 如果是活跃状态，加入缓存
      if (['pending', 'running', 'paused'].includes(session.status)) {
        this.activeSessions.set(sessionId, session);
      }
      
      return session;
    }
    
    return undefined;
  }

  /**
   * 同步获取会话（仅从缓存）
   */
  getSessionSync(sessionId: string): TaskSession | undefined {
    return this.activeSessions.get(sessionId);
  }

  /**
   * 获取用户的活跃会话
   */
  async getUserActiveSession(userId: string): Promise<TaskSession | undefined> {
    // 从缓存中查找
    for (const session of this.activeSessions.values()) {
      if (session.user_id === userId && ['pending', 'running', 'paused'].includes(session.status)) {
        return session;
      }
    }
    
    // 从数据库查找
    const dbSession = await dbManager.getUserActiveSession(userId);
    if (dbSession) {
      return this.getSession(dbSession.session_id);
    }
    
    return undefined;
  }

  /**
   * 同步获取用户活跃会话（仅从缓存）
   */
  getUserActiveSessionSync(userId: string): TaskSession | undefined {
    for (const session of this.activeSessions.values()) {
      if (session.user_id === userId && ['pending', 'running', 'paused'].includes(session.status)) {
        return session;
      }
    }
    return undefined;
  }

  /**
   * 更新会话状态
   */
  updateSessionStatus(
    sessionId: string, 
    status: TaskStatus, 
    deviceWs?: WebSocket
  ): TaskSession | undefined {
    const session = this.activeSessions.get(sessionId);
    if (!session) return undefined;

    session.status = status;
    session.updated_at = Date.now();
    if (deviceWs) {
      session.device_ws = deviceWs;
    }

    // 持久化到数据库（异步）
    dbManager.updateSessionStatus(sessionId, status).catch(err => 
      logger.error('Failed to update session status', err)
    );

    logger.info('Session status updated', { 
      session_id: sessionId, 
      status
    });

    return session;
  }

  /**
   * 更新UI事件
   */
  updateUiEvent(sessionId: string, uiEvent: UiEventMessage): TaskSession | undefined {
    const session = this.activeSessions.get(sessionId);
    if (!session) return undefined;

    session.last_ui_event = uiEvent;
    session.updated_at = Date.now();
    
    return session;
  }

  /**
   * 添加动作记录
   */
  addActionRecord(
    sessionId: string, 
    action: string, 
    reason: string,
    result?: string
  ): ActionRecord | undefined {
    const session = this.activeSessions.get(sessionId);
    if (!session) return undefined;

    const record: ActionRecord = {
      step: session.history.length + 1,
      timestamp: Date.now(),
      action,
      reason,
      result,
    };

    session.history.push(record);
    session.metrics.step_count = session.history.length;
    session.updated_at = Date.now();

    // 持久化到数据库（异步）
    dbManager.saveActionRecord(sessionId, record.step, action, reason, result).catch(err => 
      logger.error('Failed to save action record', err)
    );

    return record;
  }

  /**
   * 更新任务指标（实际Token成本）
   */
  updateMetrics(
    sessionId: string,
    inputTokens: number,
    outputTokens: number
  ): TaskSession | undefined {
    const session = this.activeSessions.get(sessionId);
    if (!session) return undefined;

    // 累加Token
    session.metrics.input_tokens += inputTokens;
    session.metrics.output_tokens += outputTokens;
    session.metrics.total_tokens = session.metrics.input_tokens + session.metrics.output_tokens;
    
    // 计算成本（基于模型）
    session.metrics.cost_usd = this.calculateCost(
      session.metrics.input_tokens,
      session.metrics.output_tokens,
      session.metrics.model
    );

    // 持久化到数据库（异步）
    dbManager.updateSessionMetrics(sessionId, session.metrics).catch(err => 
      logger.error('Failed to update session metrics', err)
    );

    return session;
  }

  /**
   * 计算Token成本（美元）
   * 基于各模型的定价
   */
  private calculateCost(inputTokens: number, outputTokens: number, model: string): number {
    // 模型定价（per 1K tokens）
    const pricing: Record<string, { input: number; output: number }> = {
      'kimi-coding/k2p5': { input: 0.003, output: 0.006 },
      'gpt-4o-mini': { input: 0.00015, output: 0.0006 },
      'gpt-4o': { input: 0.005, output: 0.015 },
      'claude-3-haiku': { input: 0.00025, output: 0.00125 },
      'claude-3-sonnet': { input: 0.003, output: 0.015 },
    };

    const modelPricing = pricing[model] || pricing['kimi-coding/k2p5'];
    
    const inputCost = (inputTokens / 1000) * modelPricing.input;
    const outputCost = (outputTokens / 1000) * modelPricing.output;
    
    return inputCost + outputCost;
  }

  /**
   * 结束会话
   */
  endSession(sessionId: string, status: 'completed' | 'failed' | 'cancelled', result?: string): TaskSession | undefined {
    const session = this.activeSessions.get(sessionId);
    if (!session) return undefined;

    session.status = status;
    session.updated_at = Date.now();

    // 添加结束记录
    if (result) {
      session.history.push({
        step: session.history.length + 1,
        timestamp: Date.now(),
        action: status,
        reason: result,
        result,
      });
      
      dbManager.saveActionRecord(sessionId, session.history.length, status, result, result).catch(err => 
        logger.error('Failed to save end record', err)
      );
    }

    // 持久化到数据库（异步）
    Promise.all([
      dbManager.updateSessionStatus(sessionId, status, result),
      dbManager.updateSessionMetrics(sessionId, session.metrics),
    ]).catch(err => logger.error('Failed to save session end state', err));

    // 从活跃缓存中移除
    this.activeSessions.delete(sessionId);

    logger.info('Session ended', { 
      session_id: sessionId, 
      status, 
      result,
      total_steps: session.history.length,
      cost_usd: session.metrics.cost_usd.toFixed(4),
      total_tokens: session.metrics.total_tokens
    });

    return session;
  }

  /**
   * 清理过期会话
   */
  private cleanupExpiredSessions(): void {
    const now = Date.now();

    for (const [sessionId, session] of this.activeSessions) {
      // 计算超时时间 - 从最后一次UI事件起算
      const lastActivity = session.last_ui_event?.timestamp || session.updated_at;
      const isExpired = now - lastActivity > config.taskTimeout;
      
      // 暂停状态超时
      const isPauseExpired = session.status === 'paused' 
        && now - lastActivity > config.pauseTimeout;
      
      // 授权等待超时
      const isAuthExpired = session.status === 'pending' 
        && now - session.created_at > config.authTimeout;

      if (isExpired || isPauseExpired || isAuthExpired) {
        const reason = isAuthExpired ? '授权超时' : (isPauseExpired ? '暂停超时' : '任务超时');
        this.endSession(sessionId, 'cancelled', reason);
      }
    }
  }

  /**
   * 销毁管理器
   */
  destroy(): void {
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
    }
    this.activeSessions.clear();
  }

  /**
   * 获取统计信息
   */
  getStats(): { totalSessions: number; activeSessions: number; userCount: number } {
    return {
      totalSessions: this.activeSessions.size,
      activeSessions: this.activeSessions.size,
      userCount: new Set(Array.from(this.activeSessions.values()).map(s => s.user_id)).size,
    };
  }
}

// 导出单例
export const sessionManager = new SessionManager();