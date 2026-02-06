import http from 'http';
import { URL } from 'url';
import { logger } from './logger';
import { sessionManager } from './session-manager';
import { wsGateway } from './websocket-gateway';
import { config } from './config';
import { dbManager } from './database';

/**
 * HTTP API Server - 提供REST API接口
 * 
 * 端点：
 * - POST /api/tasks - 创建新任务
 * - GET /api/tasks/:sessionId - 获取任务状态
 * - GET /api/tasks/:sessionId/history - 获取任务历史
 * - GET /api/stats - 获取服务器统计
 * - GET /health - 健康检查
 */
export class HttpApiServer {
  private server: http.Server | null = null;

  constructor() {}

  /**
   * 启动HTTP服务器
   */
  start(port: number = config.port, host: string = config.host): void {
    this.server = http.createServer((req, res) => {
      this.handleRequest(req, res);
    });

    this.server.listen(port, host, () => {
      logger.info('HTTP API server started', { host, port });
    });

    this.server.on('error', (error) => {
      logger.error('HTTP server error', error);
    });
  }

  /**
   * 处理HTTP请求
   */
  private async handleRequest(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    // 设置CORS头
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
      res.writeHead(200);
      res.end();
      return;
    }

    const url = new URL(req.url || '/', `http://${req.headers.host}`);
    const path = url.pathname;

    try {
      // 路由处理
      if (path === '/health') {
        await this.handleHealth(req, res);
      } else if (path === '/api/tasks' && req.method === 'POST') {
        await this.handleCreateTask(req, res);
      } else if (path.startsWith('/api/tasks/') && req.method === 'GET') {
        const sessionId = path.split('/')[3];
        if (path.endsWith('/history')) {
          await this.handleGetHistory(req, res, sessionId);
        } else {
          await this.handleGetTask(req, res, sessionId);
        }
      } else if (path === '/api/stats' && req.method === 'GET') {
        await this.handleGetStats(req, res);
      } else {
        this.sendJson(res, 404, { error: 'Not found' });
      }
    } catch (error) {
      logger.error('API request failed', error, { path });
      this.sendJson(res, 500, { error: 'Internal server error' });
    }
  }

  /**
   * 健康检查
   */
  private async handleHealth(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const wsStats = wsGateway.getStats();
    const sessionStats = sessionManager.getStats();

    this.sendJson(res, 200, {
      status: 'healthy',
      timestamp: Date.now(),
      websocket: wsStats,
      sessions: sessionStats,
    });
  }

  /**
   * 创建新任务
   */
  private async handleCreateTask(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const body = await this.readBody(req);
    const data = JSON.parse(body);

    const { user_id, goal, callback_url } = data;

    if (!user_id || !goal) {
      this.sendJson(res, 400, { error: 'Missing required fields: user_id, goal' });
      return;
    }

    // 检查用户是否已有活跃任务（同步检查缓存）
    const existingSession = sessionManager.getUserActiveSessionSync(user_id);
    if (existingSession) {
      // 结束旧任务
      sessionManager.endSession(existingSession.session_id, 'cancelled', '新任务覆盖');
    }

    // 创建新会话（传入 callback_url）
    const session = sessionManager.createSession(user_id, goal, undefined, callback_url);

    // 尝试发送控制请求给设备
    const deviceConnected = await wsGateway.sendControlRequest(user_id, session);

    if (!deviceConnected) {
      // 设备未连接，等待设备连接后再发送请求
      logger.info('Device not connected, waiting for connection', { 
        session_id: session.session_id,
        user_id 
      });
    }

    this.sendJson(res, 201, {
      session_id: session.session_id,
      user_id: session.user_id,
      goal: session.goal,
      status: session.status,
      device_connected: deviceConnected,
      message: deviceConnected 
        ? '已向设备发送接管请求，请在手机上确认'
        : '等待设备连接...',
    });
  }

  /**
   * 获取任务状态
   */
  private async handleGetTask(req: http.IncomingMessage, res: http.ServerResponse, sessionId: string): Promise<void> {
    // 先尝试从缓存获取
    let session = sessionManager.getSessionSync(sessionId);
    
    // 缓存未命中，从数据库加载
    if (!session) {
      session = await sessionManager.getSession(sessionId);
    }
    
    if (!session) {
      this.sendJson(res, 404, { error: 'Session not found' });
      return;
    }

    this.sendJson(res, 200, {
      session_id: session.session_id,
      user_id: session.user_id,
      goal: session.goal,
      status: session.status,
      created_at: session.created_at,
      updated_at: session.updated_at,
      step_count: session.history.length,
      metrics: session.metrics,
      result: session.result,
      last_ui: session.last_ui_event ? {
        package: session.last_ui_event.package,
        activity: session.last_ui_event.activity,
        element_count: session.last_ui_event.elements.length,
      } : null,
    });
  }

  /**
   * 获取任务历史
   */
  private async handleGetHistory(req: http.IncomingMessage, res: http.ServerResponse, sessionId: string): Promise<void> {
    // 先尝试从缓存获取
    let session = sessionManager.getSessionSync(sessionId);
    
    // 缓存未命中，从数据库加载
    if (!session) {
      session = await sessionManager.getSession(sessionId);
    }
    
    if (!session) {
      this.sendJson(res, 404, { error: 'Session not found' });
      return;
    }

    this.sendJson(res, 200, {
      session_id: session.session_id,
      history: session.history,
    });
  }

  /**
   * 获取服务器统计
   */
  private async handleGetStats(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const wsStats = wsGateway.getStats();
    const sessionStats = sessionManager.getStats();

    this.sendJson(res, 200, {
      server: {
        uptime: process.uptime(),
        memory: process.memoryUsage(),
      },
      websocket: wsStats,
      sessions: sessionStats,
    });
  }

  /**
   * 读取请求体
   */
  private readBody(req: http.IncomingMessage): Promise<string> {
    return new Promise((resolve, reject) => {
      let body = '';
      req.on('data', chunk => body += chunk);
      req.on('end', () => resolve(body));
      req.on('error', reject);
    });
  }

  /**
   * 发送JSON响应
   */
  private sendJson(res: http.ServerResponse, statusCode: number, data: any): void {
    res.writeHead(statusCode, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(data));
  }

  /**
   * 发送回调消息到 OpenClaw Skill
   * 统一的消息通知机制，支持多种消息类型
   */
  async sendCallback(callbackUrl: string, message: any): Promise<boolean> {
    if (!callbackUrl) {
      logger.warn('No callback URL provided, cannot send notification');
      return false;
    }

    try {
      const response = await fetch(callbackUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          ...message,
          timestamp: Date.now(),
        }),
      });

      if (!response.ok) {
        logger.error('Failed to send callback', new Error(`HTTP ${response.status}`), { 
          callback_url: callbackUrl,
          type: message.type 
        });
        return false;
      } else {
        logger.debug('Callback sent successfully', { 
          callback_url: callbackUrl,
          type: message.type 
        });
        return true;
      }
    } catch (error) {
      logger.error('Failed to send callback', error, { 
        callback_url: callbackUrl,
        type: message.type 
      });
      return false;
    }
  }

  /**
   * 停止服务器
   */
  stop(): void {
    if (this.server) {
      this.server.close();
    }
  }
}

// 导出单例
export const httpApi = new HttpApiServer();