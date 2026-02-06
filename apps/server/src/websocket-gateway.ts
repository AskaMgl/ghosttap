import WebSocket, { WebSocketServer } from 'ws';
import { config } from './config';
import { logger } from './logger';
import { sessionManager } from './session-manager';
import { aiCore } from './ai-core';
// 通知通过统一的 OpenClaw Skill 回调机制发送，不再直接使用 Feishu Webhook
import { dbManager } from './database';
import { httpApi } from './http-api';
import { 
  ClientMessage, 
  ServerMessage, 
  PingMessage,
  UiEventMessage,
  AuthorizationMessage,
  ResumeMessage,
  TaskSession,
} from './types';

/**
 * WebSocket Gateway - WebSocket服务器
 * 
 * 职责：
 * 1. 管理WebSocket连接（单设备绑定：同一user_id只能有一个连接）
 * 2. 处理心跳保活
 * 3. 路由消息到处理函数
 * 4. 管理会话生命周期
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
      logger.info('New WebSocket connection', { ip: clientIp });

      // 初始化客户端信息
      this.clients.set(ws, {
        ws,
        user_id: null,
        lastPing: Date.now(),
        isAuthenticated: false,
      });

      // 设置消息处理
      ws.on('message', (data) => {
        this.handleMessage(ws, data);
      });

      // 设置关闭处理
      ws.on('close', (code, reason) => {
        logger.info('WebSocket connection closed', { 
          code, 
          reason: reason.toString() 
        });
        this.handleDisconnect(ws);
      });

      // 设置错误处理
      ws.on('error', (error) => {
        logger.error('WebSocket error', error);
      });

      // 发送欢迎消息
      this.send(ws, { 
        type: 'pong', 
        timestamp: Date.now(), 
        server_time: Date.now() 
      });
    });

    this.wss.on('error', (error) => {
      logger.error('WebSocket server error', error);
    });

    // 启动心跳检查
    this.heartbeatChecker = setInterval(() => {
      this.checkHeartbeats();
    }, 30000); // 每30秒检查一次

    logger.info('WebSocket server started', { host, port });
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
        user_id: (message as any).user_id 
      });

      switch (message.type) {
        case 'ping':
          await this.handlePing(ws, message);
          break;
        case 'ui_event':
          await this.handleUiEvent(ws, message);
          break;
        case 'authorization':
          await this.handleAuthorization(ws, message);
          break;
        case 'resume':
          await this.handleResume(ws, message);
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
   */
  private async handlePing(ws: WebSocket, message: PingMessage): Promise<void> {
    const clientInfo = this.clients.get(ws);
    if (!clientInfo) return;

    // 更新心跳时间
    clientInfo.lastPing = Date.now();
    
    // 认证用户
    if (message.user_id && !clientInfo.isAuthenticated) {
      // 单设备绑定：如果该用户已有连接，踢掉旧连接
      const existingWs = this.userConnections.get(message.user_id);
      if (existingWs && existingWs !== ws && existingWs.readyState === WebSocket.OPEN) {
        logger.info('Single device binding: kicking old connection', { 
          user_id: message.user_id 
        });
        
        // 发送被踢通知
        this.send(existingWs, {
          type: 'task_end',
          session_id: '',
          status: 'cancelled',
          result: '已在其他设备上连接',
        });
        
        existingWs.close(1000, 'New connection from same user');
      }
      
      clientInfo.user_id = message.user_id;
      clientInfo.isAuthenticated = true;
      this.userConnections.set(message.user_id, ws);
      
      // 更新设备绑定
      await dbManager.updateDeviceBinding(message.user_id);
      
      logger.info('Client authenticated', { user_id: message.user_id });
      
      // 通过回调通知设备连接（如果有活跃会话）
      const activeSession = sessionManager.getUserActiveSessionSync(message.user_id);
      if (activeSession?.callback_url) {
        await httpApi.sendCallback(activeSession.callback_url, {
          type: 'device_connected',
          user_id: message.user_id,
        });
      }
      
      // 检查是否有待处理的会话，更新设备连接
      if (activeSession && activeSession.status === 'pending') {
        sessionManager.updateSessionStatus(activeSession.session_id, 'pending', ws as any);
      }
    }

    // 更新数据库中的最后ping时间
    if (clientInfo.user_id) {
      await dbManager.updateDeviceBinding(clientInfo.user_id);
    }

    // 回复pong
    this.send(ws, {
      type: 'pong',
      timestamp: message.timestamp,
      server_time: Date.now(),
    });
  }

  /**
   * 处理UI事件
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

    // 只有在运行状态时才进行AI决策
    if (session.status !== 'running') {
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

    try {
      // AI决策
      const { decision, inputTokens, outputTokens } = await aiCore.decide(session, message);
      
      // 记录动作
      sessionManager.addActionRecord(
        session.session_id,
        decision.action,
        decision.reason
      );

      // 更新实际Token消耗
      if (inputTokens > 0 || outputTokens > 0) {
        sessionManager.updateMetrics(session.session_id, inputTokens, outputTokens);
      }

      // 转换为命令
      const command = aiCore.decisionToCommand(decision);

      // 发送给手机
      this.send(ws, command);

      // 检查是否任务结束
      if (command.action === 'done') {
        await this.endTask(session, 'completed', decision.reason);
      } else if (command.action === 'fail') {
        await this.endTask(session, 'failed', decision.reason);
      } else if (command.action === 'pause') {
        sessionManager.updateSessionStatus(session.session_id, 'paused');
      }

    } catch (error) {
      logger.error('AI decision failed', error, { session_id: session.session_id });
      // 发送等待指令
      this.send(ws, {
        type: 'action',
        action: 'wait',
        reason: 'AI决策出错，等待重试',
        expect: '等待后重试',
        ms: 2000,
      });
    }
  }

  /**
   * 处理授权响应
   */
  private async handleAuthorization(ws: WebSocket, message: AuthorizationMessage): Promise<void> {
    const session = sessionManager.getSessionSync(message.session_id);
    if (!session) {
      logger.warn('Authorization for unknown session', { session_id: message.session_id });
      return;
    }

    if (message.decision === 'allowed') {
      logger.info('Authorization granted', { session_id: message.session_id });
      sessionManager.updateSessionStatus(message.session_id, 'running', ws as any);
      
      // 通过回调通知授权成功
      if (session.callback_url) {
        await httpApi.sendCallback(session.callback_url, {
          type: 'auth_result',
          user_id: session.user_id,
          session_id: session.session_id,
          decision: 'allowed',
          goal: session.goal,
        });
      }
    } else {
      logger.info('Authorization denied', { session_id: message.session_id });
      await this.endTask(session, 'cancelled', '用户拒绝授权');
    }
  }

  /**
   * 处理恢复请求
   */
  private async handleResume(ws: WebSocket, message: ResumeMessage): Promise<void> {
    const session = sessionManager.getSessionSync(message.session_id);
    if (!session) {
      logger.warn('Resume for unknown session', { session_id: message.session_id });
      return;
    }

    if (session.status === 'paused') {
      logger.info('Resuming session', { session_id: message.session_id });
      sessionManager.updateSessionStatus(message.session_id, 'running');
      
      // 基于最后收到的UI事件继续执行
      if (session.last_ui_event) {
        const { decision } = await aiCore.decide(session, session.last_ui_event);
        const command = aiCore.decisionToCommand(decision);
        this.send(ws, command);
      }
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
      
      // 移除设备绑定
      await dbManager.removeDeviceBinding(clientInfo.user_id);
      
      // 通过回调通知设备断开（如果有活跃会话）
      const activeSession = sessionManager.getUserActiveSessionSync(clientInfo.user_id);
      if (activeSession?.callback_url) {
        await httpApi.sendCallback(activeSession.callback_url, {
          type: 'device_disconnected',
          user_id: clientInfo.user_id,
        });
      }
      
      logger.info('Device disconnected', { user_id: clientInfo.user_id });
    }
    this.clients.delete(ws);
  }

  /**
   * 检查心跳超时
   */
  private checkHeartbeats(): void {
    const now = Date.now();
    const timeout = config.heartbeatTimeout;

    for (const [ws, clientInfo] of this.clients) {
      if (now - clientInfo.lastPing > timeout) {
        logger.warn('Heartbeat timeout, closing connection', { 
          user_id: clientInfo.user_id 
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
   * 发送控制请求给设备
   */
  async sendControlRequest(userId: string, session: TaskSession): Promise<boolean> {
    const ws = this.userConnections.get(userId);
    
    if (ws && ws.readyState === WebSocket.OPEN) {
      this.send(ws, {
        type: 'request_control',
        user_id: userId,
        session_id: session.session_id,
        goal: session.goal,
        timeout_ms: config.authTimeout,
      });
      
      // 更新会话的设备连接
      sessionManager.updateSessionStatus(session.session_id, 'pending', ws as any);
      
      // 通过回调发送授权请求通知
      if (session.callback_url) {
        await httpApi.sendCallback(session.callback_url, {
          type: 'auth_request',
          user_id: userId,
          session_id: session.session_id,
          goal: session.goal,
          timeout_sec: config.authTimeout / 1000,
        });
      }
      
      return true;
    }
    
    return false;
  }

  /**
   * 结束任务
   */
  private async endTask(session: TaskSession, status: 'completed' | 'failed' | 'cancelled', result: string): Promise<void> {
    const endedSession = sessionManager.endSession(session.session_id, status, result);
    
    if (!endedSession) return;
    
    // 发送任务结束消息到设备
    if (session.device_ws) {
      const taskStatus = status === 'completed' ? 'success' : status;
      this.send(session.device_ws as any, {
        type: 'task_end',
        session_id: session.session_id,
        status: taskStatus as 'success' | 'failed' | 'cancelled',
        result,
      });
    }
    
    // 通过回调通知 OpenClaw Skill（由 Skill 负责发送消息给用户）
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
  }
}

interface ClientInfo {
  ws: WebSocket;
  user_id: string | null;
  lastPing: number;
  isAuthenticated: boolean;
}

// 导出单例
export const wsGateway = new WebSocketGateway();