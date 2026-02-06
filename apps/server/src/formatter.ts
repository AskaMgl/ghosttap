import { UiEventMessage, Element } from './types';
import { logger } from './logger';

/**
 * UI Formatter - 将手机上报的UI数据转换为AI可读的文本格式
 * 
 * 设计原则：手机端已完成基础过滤（只上报可交互元素），
 * 云端只做格式转换，不做复杂压缩
 * 
 * 改进点：
 * - 增加了更全面的敏感操作检测
 * - 添加了更多日志记录点
 * - 优化了文本截断逻辑
 * - 增加了元素去重检测
 */
export class UiFormatter {
  // 敏感操作关键词配置（可扩展）
  private static readonly SENSITIVE_KEYWORDS = [
    { keywords: ['支付', '付款', '确认支付', '立即支付', 'pay', 'payment', 'purchase'], type: '支付' },
    { keywords: ['密码', '安全验证', '指纹验证', 'password', 'verification', 'verify', '认证'], type: '密码' },
    { keywords: ['确认付款', '立即付款', 'confirm payment'], type: '付款确认' },
    { keywords: ['转账', '汇款', 'transfer', 'send money'], type: '转账' },
    { keywords: ['充值', 'top up', 'recharge'], type: '充值' },
    { keywords: ['提现', 'withdraw'], type: '提现' },
    { keywords: ['绑定银行卡', '添加银行卡', 'bank card'], type: '银行卡绑定' },
    { keywords: ['短信验证码', '手机验证码', 'sms code', 'verification code'], type: '验证码' },
  ];

  // 加载状态关键词
  private static readonly LOADING_KEYWORDS = [
    '加载中', '正在加载', 'loading', '请稍候', '请稍等', 
    '处理中', '正在处理', 'processing', '请等待', 'please wait',
    '提交中', 'saving', '上传中', 'uploading', '下载中', 'downloading'
  ];

  // 文本长度限制
  private static readonly MAX_TEXT_LENGTH = 50;

  /**
   * 将元素类型缩写转换为可读类型
   */
  private static formatType(type: string): string {
    const typeMap: Record<string, string> = {
      'input': 'input',
      'btn': 'btn',
      'button': 'btn',
      'text': 'text',
      'txt': 'text',
      'icon': 'icon',
      'image': 'image',
      'img': 'image',
      'other': 'other',
    };
    return typeMap[type] || type;
  }

  /**
   * 截断过长文本
   */
  private static truncateText(text: string, maxLength: number = this.MAX_TEXT_LENGTH): string {
    if (!text) return '';
    if (text.length <= maxLength) return text;
    return text.substring(0, maxLength) + '...';
  }

  /**
   * 格式化单个元素为文本描述
   */
  private static formatElement(element: Element): string {
    const type = this.formatType(element.type);
    const text = element.text || element.desc || '';
    const [x, y] = element.center;
    const actions = element.actions.join(',');
    
    // 格式: [id] type "text" @ (x%, y%) [actions]
    let line = `[${element.id}] ${type}`;
    
    if (text) {
      const truncatedText = this.truncateText(text);
      line += ` "${truncatedText}"`;
    }
    
    line += ` @ (${x.toFixed(1)}%, ${y.toFixed(1)}%) [${actions}]`;
    
    return line;
  }

  /**
   * 检测并移除重复元素（基于位置和文本）
   */
  private static deduplicateElements(elements: Element[]): Element[] {
    const seen = new Set<string>();
    const unique: Element[] = [];
    
    for (const el of elements) {
      // 使用位置和文本作为唯一键
      const key = `${el.pos.join(',')}_${el.text || el.desc || ''}`;
      
      if (!seen.has(key)) {
        seen.add(key);
        unique.push(el);
      } else {
        logger.debug('Duplicate element filtered', { 
          element_id: el.id, 
          text: el.text 
        });
      }
    }
    
    if (unique.length < elements.length) {
      logger.debug('Elements deduplicated', { 
        original: elements.length, 
        unique: unique.length 
      });
    }
    
    return unique;
  }

  /**
   * 将UI事件转换为AI可读的文本格式
   */
  static formatForAi(uiEvent: UiEventMessage): string {
    const { package: pkg, activity, screen, elements, stats } = uiEvent;
    
    // 去重处理
    const uniqueElements = this.deduplicateElements(elements);
    
    const lines: string[] = [];
    
    // 屏幕信息
    lines.push(`屏幕: ${screen.width}x${screen.height} (${pkg})`);
    if (activity) {
      lines.push(`Activity: ${activity}`);
    }
    lines.push('');
    
    // 元素列表
    if (uniqueElements.length === 0) {
      lines.push('(无可见交互元素)');
    } else {
      for (const element of uniqueElements) {
        lines.push(this.formatElement(element));
      }
    }
    
    lines.push('');
    
    // 统计信息
    if (stats) {
      const compressionRate = ((stats.original_nodes - stats.filtered_nodes) / stats.original_nodes * 100).toFixed(0);
      const dedupRate = elements.length > 0 
        ? ((elements.length - uniqueElements.length) / elements.length * 100).toFixed(0)
        : '0';
      
      lines.push(`共 ${uniqueElements.length}/${stats.original_nodes} 个元素 (过滤率: ${compressionRate}%, 去重: ${dedupRate}%)`);
    } else {
      lines.push(`共 ${uniqueElements.length} 个可交互元素`);
    }
    
    return lines.join('\n');
  }

  /**
   * 格式化历史动作记录
   */
  static formatHistory(history: Array<{ step: number; action: string; reason: string; timestamp: number }>): string {
    if (history.length === 0) {
      return '（无历史动作）';
    }

    const lines = history.map(record => {
      const time = new Date(record.timestamp).toLocaleTimeString('zh-CN', { 
        hour: '2-digit', 
        minute: '2-digit', 
        second: '2-digit' 
      });
      return `- [${time}] ${record.action} (${record.reason})`;
    });

    return lines.join('\n');
  }

  /**
   * 检测敏感操作关键词
   * 根据设计文档：基于UI内容检测，比Package白名单更精准
   * 
   * 改进：使用更全面的关键词库，支持中英文
   */
  static detectSensitiveOperations(uiEvent: UiEventMessage): { isSensitive: boolean; reason?: string; confidence: 'high' | 'medium' | 'low' } {
    const detectedKeywords: Array<{ type: string; text: string; elementId: number }> = [];
    
    // 检查元素文本
    for (const element of uiEvent.elements) {
      const text = (element.text || element.desc || '').toLowerCase();
      
      if (!text) continue;
      
      for (const { keywords, type } of this.SENSITIVE_KEYWORDS) {
        if (keywords.some(kw => text.includes(kw.toLowerCase()))) {
          detectedKeywords.push({ type, text: element.text || element.desc || '', elementId: element.id });
          logger.info('Sensitive keyword detected', { 
            element_id: element.id, 
            text: element.text,
            type 
          });
        }
      }
      
      // 检查金额相关（包含 ¥ 或 元 或 $）
      const hasCurrencySymbol = text.includes('¥') || text.includes('元') || text.includes('$') || text.includes('€');
      const hasNumber = /\d/.test(text);
      
      // 检测支付按钮或金额确认（高置信度）
      if (hasCurrencySymbol && hasNumber) {
        // 检查是否同时包含支付相关词
        const paymentWords = ['支付', '付款', '购买', '充值', '转账', 'pay', 'purchase', 'buy', 'checkout'];
        const hasPaymentWord = paymentWords.some(word => text.includes(word.toLowerCase()));
        
        if (hasPaymentWord) {
          logger.info('Payment amount with action detected', { 
            element_id: element.id, 
            text: element.text,
            confidence: 'high'
          });
          return { 
            isSensitive: true, 
            reason: `检测到支付金额: ${element.text}`,
            confidence: 'high'
          };
        } else {
          // 仅金额但无明显支付动作（中等置信度）
          detectedKeywords.push({ 
            type: '金额', 
            text: element.text || '', 
            elementId: element.id 
          });
        }
      }
    }

    // 根据检测结果返回
    if (detectedKeywords.length > 0) {
      // 按优先级排序
      const highPriorityTypes = ['支付', '密码', '付款确认', '转账'];
      const highPriorityMatches = detectedKeywords.filter(d => highPriorityTypes.includes(d.type));
      
      if (highPriorityMatches.length > 0) {
        const match = highPriorityMatches[0];
        return { 
          isSensitive: true, 
          reason: `检测到${match.type}相关操作: ${match.text}`,
          confidence: 'high'
        };
      }
      
      // 多个中低优先级关键词组合也视为敏感
      if (detectedKeywords.length >= 2) {
        const types = [...new Set(detectedKeywords.map(d => d.type))];
        return { 
          isSensitive: true, 
          reason: `检测到多个敏感操作关键词: ${types.join(', ')}`,
          confidence: 'medium'
        };
      }
      
      // 单个中低优先级
      const match = detectedKeywords[0];
      return { 
        isSensitive: true, 
        reason: `检测到${match.type}相关操作: ${match.text}`,
        confidence: 'low'
      };
    }

    return { isSensitive: false, confidence: 'low' };
  }

  /**
   * 检查是否处于加载状态
   * 
   * 改进：增加了更多加载状态检测，包括骨架屏检测
   */
  static isLoadingState(uiEvent: UiEventMessage): { isLoading: boolean; reason?: string } {
    // 检查是否有加载相关的文本
    for (const element of uiEvent.elements) {
      const text = (element.text || '').toLowerCase();
      if (!text) continue;
      
      for (const keyword of this.LOADING_KEYWORDS) {
        if (text.includes(keyword.toLowerCase())) {
          logger.debug('Loading state detected by text', { text: element.text });
          return { isLoading: true, reason: `检测到加载状态: ${element.text}` };
        }
      }
    }

    // 检查元素数量是否过少（可能是骨架屏或正在加载）
    if (uiEvent.stats) {
      // 原始节点多但过滤后很少，可能是骨架屏
      if (uiEvent.stats.original_nodes > 10 && uiEvent.stats.filtered_nodes < 3) {
        logger.debug('Skeleton screen suspected', { 
          original: uiEvent.stats.original_nodes,
          filtered: uiEvent.stats.filtered_nodes
        });
        return { isLoading: true, reason: '界面元素过少，可能正在加载或显示骨架屏' };
      }
    } else if (uiEvent.elements.length < 3) {
      // 没有stats但元素很少
      return { isLoading: true, reason: '界面元素过少，可能正在加载' };
    }

    return { isLoading: false };
  }

  /**
   * 统计界面元素类型分布
   */
  static getElementStats(elements: Element[]): Record<string, number> {
    const stats: Record<string, number> = {};
    
    for (const el of elements) {
      const type = this.formatType(el.type);
      stats[type] = (stats[type] || 0) + 1;
    }
    
    return stats;
  }

  /**
   * 格式化元素统计信息（用于调试日志）
   */
  static formatElementStats(elements: Element[]): string {
    const stats = this.getElementStats(elements);
    const parts = Object.entries(stats).map(([type, count]) => `${type}:${count}`);
    return parts.join(', ');
  }
}
