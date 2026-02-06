import { config } from './config';
import { logger } from './logger';

/**
 * Feishu 通知服务
 * 支持 Webhook 机器人通知
 */
export class FeishuNotifier {
  private webhookUrl?: string;

  constructor() {
    this.webhookUrl = config.feishuWebhook;
  }

  /**
   * 发送文本消息
   */
  async sendText(text: string, userId?: string): Promise<boolean> {
    if (!this.webhookUrl) {
      logger.debug('Feishu webhook not configured, skipping notification');
      return false;
    }

    try {
      const response = await fetch(this.webhookUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          msg_type: 'text',
          content: {
            text,
          },
        }),
      });

      if (!response.ok) {
        logger.error('Failed to send Feishu notification', new Error(`HTTP ${response.status}`));
        return false;
      }

      logger.debug('Feishu notification sent', { text: text.substring(0, 100) });
      return true;
    } catch (error) {
      logger.error('Failed to send Feishu notification', error);
      return false;
    }
  }

  /**
   * 发送任务完成通知
   */
  async notifyTaskCompleted(
    userId: string,
    goal: string,
    status: 'completed' | 'failed' | 'cancelled',
    result: string,
    steps: number,
    costUsd: number
  ): Promise<boolean> {
    const statusEmoji = status === 'completed' ? '✅' : status === 'failed' ? '❌' : '⚠️';
    const statusText = status === 'completed' ? '完成' : status === 'failed' ? '失败' : '已取消';
    const costText = costUsd > 0 ? `\n💰 消耗: $${costUsd.toFixed(4)}` : '';

    const text = `${statusEmoji} 任务${statusText}\n\n🎯 目标: ${goal}\n📊 结果: ${result}\n📝 步骤: ${steps}${costText}`;

    return this.sendText(text, userId);
  }

  /**
   * 发送授权请求通知
   */
  async notifyAuthRequest(userId: string, goal: string, timeoutSec: number): Promise<boolean> {
    const text = `🤖 新的自动化任务\n\n🎯 目标: ${goal}\n⏰ 请在 ${timeoutSec} 秒内在手机上确认授权`;
    return this.sendText(text, userId);
  }

  /**
   * 发送设备连接通知
   */
  async notifyDeviceConnected(userId: string, connected: boolean): Promise<boolean> {
    const text = connected 
      ? `📱 设备已连接\n\n您的手机已连接到 GhostTap 服务，可以开始自动化任务了。`
      : `📱 设备已断开\n\n您的手机与 GhostTap 服务断开连接。`;
    return this.sendText(text, userId);
  }
}

// 导出单例
export const feishuNotifier = new FeishuNotifier();