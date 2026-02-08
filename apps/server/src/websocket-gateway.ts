import WebSocket, { WebSocketServer } from 'ws';
import { config } from './config';
import { logger } from './logger';
import { sessionManager } from './session-manager';
import { aiCore } from './ai-core';
import { dbManager } from './database';
import { httpApi } from './http-api';
import { UiFormatter } from './formatter';
import { 
  ClientMessage, 
  ServerMessage, 
  PingMessage,
  UiEventMessage,
  PauseRequest,
  ResumeRequest,
  StopRequest,
  ErrorEvent,
  SessionContext,
  TaskResume,
  ClientInfo,
} from './types';

/**
 * WebSocket Gateway - WebSocket服务器
 * 
 * v3.12 全面重构:
 * 1. 认证方式改为URL参数解析（?user_id=xxx&device_name=xxx）
 * 2. 移除 sendControlRequest 授权流程
 * 3. 任务创建后直接发送 TaskStart
 * 4. 添加 StopRequest、ErrorEvent 消息处理
 * 5. 断连恢复时发送 TaskResume
 * 6. 添加 session 级别的 aiCallInFlight 互斥锁
 * 7. 过期决策丢弃改用 timestamp 比较
 * 8. 心跳间隔改为90秒
 * 9. 添加第一层敏感词硬检测（调用AI前）
 */
export class WebSocketGateway {
  private wss: WebSocketServer | null = null;
  private clients: Map<WebSocket, ClientInfo> = new Map();
  private userConnections: Map<string, WebSocket> = new Map(); // user_id -> ws (单设备绑定)
  private heartbeatChecker: NodeJS.Timeout | null = null;

  constructor() {}

  /**
   * 启动WebSocket服务器
   */
  start(port: number = config.port, host: string = config.host): void {
    this.wss = new WebSocketServer({ port, host });

    this.wss.on('connection', (ws, req) => {
      const clientIp = req.socket.remoteAddress;
      
      // v3.12: 从URL参数解析认证信息
      const url = new URL(req.url || '/', `http://${req.headers.host}`);
      const userId = url.searchParams.get('user_id');
      const deviceName = url.searchParams.get('device_name') || '未知设备';
      
      logger.info('New WebSocket connection', { 
        ip: clientIp, 
        user_id: userId,
        device_name: deviceName 
      });

      // v3.12: 验证 user_id
      if (!userId) {
        logger.warn('Connection rejected: missing user_id');
        ws.close(1002, 'Missing user_id parameter');
        return;
      }

      // v3.12: 白名单验证
      if (!config.allowedUserIds.has(userId)) {
        logger.warn('Connection rejected: user_id not in whitelist', { user_id: userId });
        ws.close(1002, 'Invalid user_id');
        return;
      }

      // v3.12: 单设备绑定 - 如果该用户已有连接，踢掉旧连接
      const existingWs = this.userConnections.get(userId);
      if (existingWs && existingWs !== ws && existingWs.readyState === WebSocket.OPEN) {
        logger.info('Single device binding: kicking old connection', { user_id: userId });
        
        this.send(existingWs, {
          type: 'task_end',
          session_id: '',
          status: 'cancelled',
          result: '已在其他设备上连接',
        });
        
        existingWs.close(1000, 'New connection from same user');
        this.clients.delete(existingWs);
      }

      // 初始化客户端信息
      const clientInfo: ClientInfo = {
        ws,
        user_id: userId,
        device_name: deviceName,
        lastPing: Date.now(),
        isAuthenticated: true, // v3.12: URL认证，立即通过
      };
      
      this.clients.set(ws, clientInfo);
      this.userConnections.set(userId, ws);
      
      // v3.12: 更新设备绑定（包含 device_name）
      dbManager.updateDeviceBinding(userId, deviceName).catch(err => 
        logger.error('Failed to update device binding', err)
      );

      logger.info('Client authenticated via URL', { user_id: userId, device_name: deviceName });

      // v3.12: 断连恢复检查 - 如果有running/paused状态的任务，发送TaskResume
      this.handleReconnectResume(userId, ws);

      // 设置消息处理
      ws.on('message', (data) => {
        this.handleMessage(ws, data);
      });

      // 设置关闭处理
      ws.on('close', (code, reason) => {
        logger.info('WebSocket connection closed', { 
          user_id: userId,
          code, 
          reason: reason.toString() 
        });
        this.handleDisconnect(ws);
      });

      // 设置错误处理
      ws.on('error', (error) => {
        logger.error('WebSocket error', error, { user_id: userId });
      });

      // v3.12: 发送欢迎pong
      this.send(ws, { 
        type: 'pong', 
        timestamp: Date.now()
      });
    });

    this.wss.on('error', (error) => {
      logger.error('WebSocket server error', error);
    });

    // v3.12: 心跳检查间隔改为30秒（心跳超时是3分钟，每30秒检查一次）
    this.heartbeatChecker = setInterval(() => {
      this.checkHeartbeats();
    }, 30000);

    logger.info('WebSocket server started', { host, port });
  }

  /**
   * v3.14: 断连恢复处理
   * 检查是否在宽限期内，如果是则恢复任务
   */
  private async handleReconnectResume(userId: string, ws: WebSocket): Promise<void> {
    // v3.14: 使用新的 handleDeviceReconnect 方法处理重连
    const session = sessionManager.handleDeviceReconnect(userId, ws as any);
    
    if (session && (session.status === 'running' || session.status === 'paused')) {
      logger.info('Resuming task after reconnect', { 
        session_id: session.session_id,
        user_id: userId,
        status: session.status
      });
      
      // 发送 TaskResume 消息
      const resumeMsg: TaskResume = {
        type: 'task_resume',
        session_id: session.session_id,
        goal: session.goal,
        status: session.status,
        reason: session.status === 'paused' ? '用户暂停' : undefined,
      };
      
      this.send(ws, resumeMsg);
    }
  }

  /**
   * 处理收到的消息
   */
  private async handleMessage(ws: WebSocket, data: WebSocket.RawData): Promise<void> {
    const clientInfo = this.clients.get(ws);
    if (!clientInfo) return;

    try {
      const message = JSON.parse(data.toString()) as ClientMessage;
      
      logger.debug('Received message', { 
        type: message.type, 
        user_id: clientInfo.user_id 
      });

      switch (message.type) {
        case 'ping':
          await this.handlePing(ws, message);
          break;
        case 'ui_event':
          await this.handleUiEvent(ws, message);
          break;
        case 'pause':
          await this.handlePause(ws, message);
          break;
        case 'resume':
          await this.handleResume(ws, message);
          break;
        case 'stop': // v3.12: 新增 StopRequest 处理
          await this.handleStop(ws, message);
          break;
        case 'error': // v3.12: 新增 ErrorEvent 处理
          await this.handleError(ws, message);
          break;
        default:
          logger.warn('Unknown message type', { type: (message as any).type });
      }
    } catch (error) {
      logger.error('Failed to handle message', error);
    }
  }

  /**
   * 处理心跳消息
   * v3.12: Ping移除user_id，Pong移除server_time
   */
  private async handlePing(ws: WebSocket, message: PingMessage): Promise<void> {
    const clientInfo = this.clients.get(ws);
    if (!clientInfo) return;

    // 更新心跳时间
    clientInfo.lastPing = Date.now();
    
    // 更新数据库中的最后ping时间
    if (clientInfo.user_id) {
      await dbManager.updateDeviceBinding(clientInfo.user_id);
    }

    // v3.12: 回复pong（只回显timestamp）
    this.send(ws, {
      type: 'pong',
      timestamp: message.timestamp,
    });
  }

  /**
   * 处理UI事件
   * v3.12: 
   * - 添加第一层敏感词硬检测（调用AI前）
   * - 使用 timestamp 比较进行过期决策丢弃
   * - 添加 aiCallInFlight 互斥锁
   */
  private async handleUiEvent(ws: WebSocket, message: UiEventMessage): Promise<void> {
    const clientInfo = this.clients.get(ws);
    if (!clientInfo || !clientInfo.isAuthenticated) {
      logger.warn('Unauthenticated UI event');
      return;
    }

    // 更新会话的UI事件
    const session = sessionManager.updateUiEvent(message.session_id, message);
    if (!session) {
      logger.warn('UI event for unknown session', { session_id: message.session_id });
      return;
    }

    // v3.12: 只有在 running 状态时才处理UI事件
    if (session.status !== 'running') {
      logger.debug('Ignoring UI event, session not running', { 
        session_id: session.session_id,
        status: session.status 
      });
      return;
    }

    // 检查是否超过最大步骤数
    if (session.history.length >= config.maxSteps) {
      logger.warn('Max steps reached, ending session', { 
        session_id: session.session_id,
        steps: session.history.length 
      });
      await this.endTask(session, 'failed', '超过最大步骤数限制');
      return;
    }

    // v3.12: 第一层敏感词硬检测（零成本、零延迟、不可绕过）
    const sensitiveCheck = UiFormatter.detectSensitiveOperations(message);
    if (sensitiveCheck.isSensitive) {
      logger.info('Sensitive operation detected (first layer), pausing', { 
        session_id: session.session_id,
        reason: sensitiveCheck.reason 
      });
      
      // 发送pause指令
      this.send(ws, {
        type: 'action',
        action: 'pause',
        reason: sensitiveCheck.reason!,
      });
      
      // 更新会话状态为paused
      sessionManager.updateSessionStatus(session.session_id, 'paused');
      
      // 记录动作
      sessionManager.addActionRecord(
        session.session_id,
        'pause',
        sensitiveCheck.reason!
      );
      
      return;
    }

    // v3.12: AI调用互斥锁检查
    if (session.aiCallInFlight) {
      logger.debug('AI call already in flight, skipping', { 
        session_id: session.session_id 
      });
      return;
    }

    // v3.12: 记录当前UI事件的时间戳，用于过期检测
    const currentUiTimestamp = message.timestamp;

    // 设置AI调用锁
    sessionManager.setAiCallInFlight(session.session_id, true);

    try {
      // v3.12: 使用循环处理过期决策丢弃
      let decision;
      let inputTokens = 0;
      let outputTokens = 0;
      let retryCount = 0;
      const maxRetries = 3;

      while (retryCount < maxRetries) {
        // 获取当前最新的UI事件时间戳
        const latestSession = sessionManager.getSessionSync(session.session_id);
        const latestTimestamp = latestSession?.latestUiTimestamp || currentUiTimestamp;

        // AI决策
        const result = await aiCore.decide(session, message);
        decision = result.decision;
        inputTokens = result.inputTokens;
        outputTokens = result.outputTokens;

        // v3.12: 使用 timestamp 比较判断UI是否已过期
        if (latestTimestamp === currentUiTimestamp) {
          // UI未变化，决策有效
          break;
        }

        // UI已更新，丢弃旧决策，继续循环处理最新UI
        logger.warn('UI changed during AI thinking, discarding stale decision', {
          session_id: session.session_id,
          retry: retryCount + 1
        });
        retryCount++;
      }

      if (retryCount >= maxRetries) {
        logger.error('Max retries exceeded for stale decision, giving up', {
          session_id: session.session_id
        });
        return;
      }

      // 记录动作
      sessionManager.addActionRecord(
        session.session_id,
        decision!.action,
        decision!.reason
      );

      // 更新实际Token消耗
      if (inputTokens > 0 || outputTokens > 0) {
        sessionManager.updateMetrics(session.session_id, inputTokens, outputTokens);
      }

      // v3.12: 处理 done/fail 终止指令
      if (decision!.action === 'done') {
        await this.endTask(session, 'completed', decision!.reason);
        return;
      } else if (decision!.action === 'fail') {
        await this.endTask(session, 'failed', decision!.reason);
        return;
      }

      // 转换为命令
      const command = aiCore.decisionToCommand(decision!);

      // v3.12: 再次检查状态（防止AI调用期间状态改变）
      const currentSession = sessionManager.getSessionSync(session.session_id);
      if (currentSession?.status !== 'running') {
        logger.debug('Session no longer running, discarding decision', {
          session_id: session.session_id,
          status: currentSession?.status
        });
        return;
      }

      // 发送给手机
      this.send(ws, command);

      // v3.12: 检查是否需要暂停
      if (command.action === 'pause') {
        sessionManager.updateSessionStatus(session.session_id, 'paused');
      }

    } catch (error) {
      logger.error('AI decision failed', error, { session_id: session.session_id });
      
      // v3.13: 记录 AI 失败并检查是否应该暂停任务
      const shouldPause = sessionManager.recordAiFailure(session.session_id);
      
      if (shouldPause) {
        // 连续3次失败，暂停任务并通知用户
        sessionManager.updateSessionStatus(session.session_id, 'paused');
        this.send(ws, {
          type: 'action',
          action: 'pause',
          reason: 'AI服务连续不可用，任务已暂停，请检查AI配置后点击继续',
        });
        
        // 通过回调通知用户
        if (session.callback_url) {
          await httpApi.sendCallback(session.callback_url, {
            type: 'task_completed',
            user_id: session.user_id,
            session_id: session.session_id,
            status: 'paused',
            result: 'AI服务暂时不可用，任务已暂停',
            goal: session.goal,
            steps: session.history.length,
            cost_usd: session.metrics.cost_usd,
          });
        }
      } else {
        // 发送等待指令，稍后重试
        this.send(ws, {
          type: 'action',
          action: 'wait',
          wait_ms: 2000,
          reason: 'AI决策出错，等待重试',
        });
      }
    } finally {
      // 释放AI调用锁
      sessionManager.setAiCallInFlight(session.session_id, false);
    }
  }

  /**
   * 处理暂停请求（用户点击悬浮窗暂停按钮）
   */
  private async handlePause(ws: WebSocket, message: PauseRequest): Promise<void> {
    const session = sessionManager.getSessionSync(message.session_id);
    if (!session) {
      logger.warn('Pause for unknown session', { session_id: message.session_id });
      return;
    }

    if (session.status === 'running') {
      logger.info('User paused session', { session_id: message.session_id });
      sessionManager.updateSessionStatus(message.session_id, 'paused');
      sessionManager.addActionRecord(
        message.session_id,
        'pause',
        '用户点击暂停按钮'
      );
    }
  }

  /**
   * 处理恢复请求（用户点击悬浮窗继续按钮）
   */
  private async handleResume(ws: WebSocket, message: ResumeRequest): Promise<void> {
    const session = sessionManager.getSessionSync(message.session_id);
    if (!session) {
      logger.warn('Resume for unknown session', { session_id: message.session_id });
      return;
    }

    if (session.status === 'paused') {
      logger.info('User resumed session', { session_id: message.session_id });
      sessionManager.updateSessionStatus(message.session_id, 'running');
      sessionManager.addActionRecord(
        message.session_id,
        'resume',
        '用户点击继续按钮'
      );
      
      // v3.12: 不立即执行AI决策，等待手机端重新上报UI事件
      // 手机端会在发送 resume 后立即上报当前UI
    }
  }

  /**
   * v3.12: 新增 StopRequest 处理（用户点击悬浮窗结束按钮）
   */
  private async handleStop(ws: WebSocket, message: StopRequest): Promise<void> {
    const session = sessionManager.getSessionSync(message.session_id);
    if (!session) {
      logger.warn('Stop for unknown session', { session_id: message.session_id });
      return;
    }

    logger.info('User stopped session', { session_id: message.session_id });
    await this.endTask(session, 'cancelled', '用户点击结束按钮');
  }

  /**
   * v3.12: 新增 ErrorEvent 处理（手机端执行动作失败）
   */
  private async handleError(ws: WebSocket, message: ErrorEvent): Promise<void> {
    const session = sessionManager.getSessionSync(message.session_id);
    if (!session) {
      logger.warn('Error for unknown session', { session_id: message.session_id });
      return;
    }

    logger.error('Client reported error', { 
      session_id: message.session_id,
      error: message.error,
      message: message.message
    });

    // 记录错误
    sessionManager.addActionRecord(
      message.session_id,
      'error',
      `${message.error}: ${message.message}`
    );

    // 如果是PACKAGE_NOT_FOUND等致命错误，结束任务
    if (message.error === 'PACKAGE_NOT_FOUND') {
      await this.endTask(session, 'failed', message.message);
    }
  }

  /**
   * 处理断开连接
   */
  private async handleDisconnect(ws: WebSocket): Promise<void> {
    const clientInfo = this.clients.get(ws);
    if (clientInfo?.user_id) {
      // 从用户连接映射中移除
      this.userConnections.delete(clientInfo.user_id);
      
      logger.info('Device disconnected', { user_id: clientInfo.user_id });
      
      // v3.14: 处理设备断开，启动断连宽限期（30秒）
      sessionManager.handleDeviceDisconnect(clientInfo.user_id);
    }
    this.clients.delete(ws);
  }

  /**
   * 检查心跳超时
   * v3.12: 心跳间隔90秒，超时时间3分钟
   */
  private checkHeartbeats(): void {
    const now = Date.now();
    const timeout = config.heartbeatTimeout; // 3分钟

    for (const [ws, clientInfo] of this.clients) {
      if (now - clientInfo.lastPing > timeout) {
        logger.warn('Heartbeat timeout, closing connection', { 
          user_id: clientInfo.user_id,
          lastPing: clientInfo.lastPing,
          timeout
        });
        ws.terminate();
        this.clients.delete(ws);
        if (clientInfo.user_id) {
          this.userConnections.delete(clientInfo.user_id);
        }
      }
    }
  }

  /**
   * 发送消息给客户端
   */
  send(ws: WebSocket, message: ServerMessage): void {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(message));
    }
  }

  /**
   * v3.12: 检查设备是否已连接
   */
  isDeviceConnected(userId: string): boolean {
    const ws = this.userConnections.get(userId);
    return ws !== undefined && ws.readyState === WebSocket.OPEN;
  }

  /**
   * v3.12: 发送 TaskStart 消息（替代原来的 sendControlRequest 授权流程）
   */
  sendTaskStart(userId: string, session: SessionContext): boolean {
    const ws = this.userConnections.get(userId);
    
    if (ws && ws.readyState === WebSocket.OPEN) {
      this.send(ws, {
        type: 'task_start',
        session_id: session.session_id,
        goal: session.goal,
      });
      
      // 更新会话的设备连接
      sessionManager.updateSessionStatus(session.session_id, 'running', ws as any);
      
      logger.info('TaskStart sent to device', { 
        session_id: session.session_id,
        user_id: userId 
      });
      
      return true;
    }
    
    return false;
  }

  /**
   * 结束任务
   * v3.12: 回调只发送 task_completed
   */
  private async endTask(session: SessionContext, status: 'completed' | 'failed' | 'cancelled', result: string): Promise<void> {
    const endedSession = sessionManager.endSession(session.session_id, status, result);
    
    if (!endedSession) return;
    
    // 发送任务结束消息到设备
    const ws = this.userConnections.get(session.user_id);
    if (ws && ws.readyState === WebSocket.OPEN) {
      const taskStatus = status === 'completed' ? 'success' : status;
      this.send(ws, {
        type: 'task_end',
        session_id: session.session_id,
        status: taskStatus as 'success' | 'failed' | 'cancelled',
        result,
      });
    }
    
    // v3.12: 只通过回调发送 task_completed
    if (session.callback_url) {
      await httpApi.sendCallback(session.callback_url, {
        type: 'task_completed',
        user_id: session.user_id,
        session_id: session.session_id,
        status,
        result,
        goal: session.goal,
        steps: endedSession.metrics.step_count,
        cost_usd: endedSession.metrics.cost_usd,
      });
    } else {
      logger.warn('No callback URL for session, cannot notify user', { session_id: session.session_id });
    }
  }

  /**
   * 获取连接统计
   */
  getStats(): { connectedClients: number; authenticatedClients: number } {
    let authenticated = 0;
    for (const client of this.clients.values()) {
      if (client.isAuthenticated) authenticated++;
    }
    return {
      connectedClients: this.clients.size,
      authenticatedClients: authenticated,
    };
  }

  /**
   * 停止服务器
   */
  stop(): void {
    if (this.heartbeatChecker) {
      clearInterval(this.heartbeatChecker);
    }
    if (this.wss) {
      this.wss.close();
    }
    this.clients.clear();
    this.userConnections.clear();
    logger.info('WebSocket server stopped');
  }
}

// 导出单例
export const wsGateway = new WebSocketGateway();