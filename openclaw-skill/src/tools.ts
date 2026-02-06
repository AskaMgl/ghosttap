/**
 * GhostTap Skill - OpenClaw Agent Tool
 * 
 * 此工具由 OpenClaw 调用，用于创建 GhostTap 任务
 * 同时提供 HTTP 回调接口接收各种状态通知
 */

import http from 'http';

const GHOSTTAP_API_URL = process.env.GHOSTTAP_API_URL || "http://localhost:8081";

// 存储待回复的消息上下文
const pendingReplies: Map<string, { 
  userId: string; 
  sessionId: string;
  replyFn: (text: string) => void;
}> = new Map();

interface CreateTaskParams {
  user_id: string;
  goal: string;
  replyFn: (text: string) => void;  // OpenClaw 提供的回复函数
}

interface CreateTaskResult {
  success: boolean;
  session_id?: string;
  device_connected?: boolean;
  message?: string;
  error?: string;
  device_not_connected?: boolean;
}

/**
 * 创建 GhostTap 任务
 * 
 * @param params.user_id - 用户唯一标识
 * @param params.goal - 任务目标描述
 * @param params.replyFn - OpenClaw 提供的回复函数，用于稍后发送结果
 * @returns 任务创建结果
 */
export async function ghosttap_create_task(params: CreateTaskParams): Promise<CreateTaskResult> {
  try {
    // 注册回调地址（让 GhostTap 任务状态变更时通知我们）
    const callbackUrl = await startCallbackServer(params.replyFn);

    const response = await fetch(`${GHOSTTAP_API_URL}/api/tasks`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        user_id: params.user_id,
        goal: params.goal,
        callback_url: callbackUrl,  // 告知 GhostTap 通过此地址回调
      }),
    });

    if (!response.ok) {
      const error = await response.text();
      return {
        success: false,
        error: `HTTP ${response.status}: ${error}`,
      };
    }

    const data = await response.json() as { 
      session_id: string; 
      device_connected: boolean; 
      message: string;
    };

    // 存储会话和回复函数的映射
    pendingReplies.set(data.session_id, {
      userId: params.user_id,
      sessionId: data.session_id,
      replyFn: params.replyFn,
    });

    return {
      success: true,
      session_id: data.session_id,
      device_connected: data.device_connected,
      message: data.message,
      device_not_connected: !data.device_connected,
    };
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : "Unknown error",
    };
  }
}

/**
 * 获取任务状态
 * 
 * @param session_id - 任务会话ID
 */
export async function ghosttap_get_task(session_id: string): Promise<any> {
  try {
    const response = await fetch(`${GHOSTTAP_API_URL}/api/tasks/${session_id}`);
    
    if (!response.ok) {
      return { error: `HTTP ${response.status}` };
    }

    return await response.json();
  } catch (error) {
    return { error: error instanceof Error ? error.message : "Unknown error" };
  }
}

/**
 * 健康检查
 */
export async function ghosttap_health(): Promise<{ healthy: boolean; message?: string }> {
  try {
    const response = await fetch(`${GHOSTTAP_API_URL}/health`);
    
    if (!response.ok) {
      return { healthy: false, message: `HTTP ${response.status}` };
    }

    const data = await response.json() as { status: string };
    return { healthy: data.status === "healthy", message: data.status };
  } catch (error) {
    return { 
      healthy: false, 
      message: error instanceof Error ? error.message : "Unknown error" 
    };
  }
}

// 回调服务器
let callbackServer: http.Server | null = null;
let callbackPort = 18081;  // 默认回调端口

/**
 * 启动回调服务器接收 GhostTap 通知
 */
async function startCallbackServer(replyFn: (text: string) => void): Promise<string> {
  if (callbackServer) {
    return `http://localhost:${callbackPort}/callback`;
  }

  return new Promise((resolve, reject) => {
    callbackServer = http.createServer((req, res) => {
      handleCallback(req, res, replyFn);
    });

    callbackServer.listen(callbackPort, () => {
      console.log(`[GhostTap Skill] Callback server started on port ${callbackPort}`);
      resolve(`http://localhost:${callbackPort}/callback`);
    });

    callbackServer.on('error', (err) => {
      if ((err as any).code === 'EADDRINUSE') {
        // 端口被占用，尝试下一个
        callbackPort++;
        callbackServer?.close();
        callbackServer = null;
        startCallbackServer(replyFn).then(resolve).catch(reject);
      } else {
        reject(err);
      }
    });
  });
}

/**
 * 处理 GhostTap 回调
 * 支持多种消息类型：task_completed, auth_request, auth_result, device_connected, device_disconnected
 */
function handleCallback(req: http.IncomingMessage, res: http.ServerResponse, defaultReplyFn: (text: string) => void): void {
  if (req.method !== 'POST' || req.url !== '/callback') {
    res.writeHead(404);
    res.end('Not found');
    return;
  }

  let body = '';
  req.on('data', chunk => body += chunk);
  req.on('end', () => {
    try {
      const data = JSON.parse(body);
      const { type, session_id, user_id } = data;
      
      // 查找对应的回复函数
      const pending = session_id ? pendingReplies.get(session_id) : null;
      const replyFn = pending?.replyFn || defaultReplyFn;
      
      let message = '';
      
      switch (type) {
        case 'task_completed': {
          const { status, result, goal, steps, cost_usd } = data;
          const statusEmoji = status === 'completed' ? '✅' : status === 'failed' ? '❌' : '⚠️';
          const statusText = status === 'completed' ? '完成' : status === 'failed' ? '失败' : '已取消';
          const costText = cost_usd > 0 ? `\n💰 消耗: $${cost_usd.toFixed(4)}` : '';
          
          message = `${statusEmoji} 任务${statusText}\n\n🎯 目标: ${goal}\n📊 结果: ${result}\n📝 步骤: ${steps}${costText}`;
          
          // 任务完成，清理映射
          if (session_id) {
            pendingReplies.delete(session_id);
          }
          break;
        }
        
        case 'auth_request': {
          const { goal, timeout_sec } = data;
          message = `🤖 新的自动化任务\n\n🎯 目标: ${goal}\n⏰ 请在 ${timeout_sec} 秒内在手机上确认授权`;
          break;
        }
        
        case 'auth_result': {
          const { decision, goal } = data;
          if (decision === 'allowed') {
            message = `✅ 已获授权，开始执行任务：${goal}`;
          } else {
            message = `❌ 用户拒绝授权，任务已取消：${goal}`;
            // 授权被拒绝，清理映射
            if (session_id) {
              pendingReplies.delete(session_id);
            }
          }
          break;
        }
        
        case 'device_connected': {
          message = `📱 设备已连接\n\n您的手机已连接到 GhostTap 服务，可以开始自动化任务了。`;
          break;
        }
        
        case 'device_disconnected': {
          message = `📱 设备已断开\n\n您的手机与 GhostTap 服务断开连接。`;
          break;
        }
        
        default:
          console.log(`[GhostTap Skill] Unknown callback type: ${type}`);
          res.writeHead(400);
          res.end('Unknown callback type');
          return;
      }
      
      // 发送通知
      if (message) {
        replyFn(message);
      }
      
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true }));
      
    } catch (error) {
      console.error('[GhostTap Skill] Failed to handle callback:', error);
      res.writeHead(400);
      res.end('Invalid JSON');
    }
  });
}
