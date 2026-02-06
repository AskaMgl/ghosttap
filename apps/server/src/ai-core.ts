import { UiEventMessage, AiDecision, ActionCommandMessage, TaskSession } from './types';
import { config } from './config';
import { logger } from './logger';
import { UiFormatter } from './formatter';

/**
 * AI Core - 调用LLM进行决策
 * 
 * 职责：根据当前UI状态和任务目标，选择最合适的下一个动作
 * 
 * 改进点：
 * - 添加了完整的重试机制（指数退避）
 * - 细化了错误分类和处理
 * - 添加了网络错误检测
 * - 增加了重试日志
 */
export class AiCore {
  private systemPrompt: string;
  private retryCount: Map<string, number>; // 每个session的重试计数

  constructor() {
    this.systemPrompt = this.buildSystemPrompt();
    this.retryCount = new Map();
  }

  /**
   * 构建系统Prompt
   */
  private buildSystemPrompt(): string {
    return `你是一个手机自动化助手，负责控制用户的Android手机完成指定任务。

## 你的职责
1. 分析当前界面状态
2. 根据用户目标选择最合适的动作
3. 以JSON格式返回决策

## 输入格式
屏幕: {width}x{height} ({package})

[element_id] type "text" @ (x%, y%) [available_actions]
...

共 {count} 个可交互元素

用户目标: {goal}

历史动作:
- [{timestamp}] {action} ({reason})

## 输出格式
必须返回有效的JSON，格式如下:
{
  "thought": "你的分析过程，包括当前界面状态、目标进度、下一步计划",
  "action": "动作名称",
  "target": {
    "element_id": 元素ID,
    "center": [x百分比, y百分比]
  },
  "text": "输入文本（input动作时）",
  "reason": "选择这个动作的原因",
  "expect": "执行后预期会发生什么"
}

## 可用动作
- click: 点击指定元素
- input: 在指定输入框输入文字（需同时提供text字段）
- swipe: 滑动屏幕（需同时提供direction字段: up/down/left/right）
- back: 返回上一级
- wait: 等待指定毫秒（用于等待页面加载）
- pause: 暂停任务，请求人工接管（敏感操作）
- done: 任务完成
- fail: 任务无法完成

## 规则
1. 所有坐标使用百分比 [0-100, 0-100]
2. 输入文字前必须先点击对应输入框
3. 找不到目标时尝试滑动查找或返回
4. 连续3次无进展时放弃并报错
5. 遇到弹窗优先处理弹窗

## 页面加载等待策略
当界面显示以下状态时，必须输出wait而非立即执行：
- "加载中"、"正在加载"等文字提示
- 转圈动画、骨架屏
- 元素明显不全（如只有标题栏，内容区空白）
- 网络请求提示

正确做法：
\`\`\`json
{
  "thought": "页面显示'加载中'转圈，内容尚未加载完成",
  "action": "wait",
  "params": {"ms": 1000},
  "reason": "等待页面加载完成",
  "expect": "1秒后收到新的UI事件，显示完整页面内容"
}
\`\`\`

## 敏感操作检测（关键！）
**当界面出现以下情况时，必须输出pause()：**
1. 包含"支付"、"密码"、"确认付款"等敏感词
2. 涉及金额输入或确认（包含"¥"或"元"且涉及交易）
3. 其他需要用户亲自确认的操作

**示例：**
界面出现'确认支付¥99.00'按钮 → 输出pause，reason写"检测到支付确认，需要用户亲自确认"
界面出现'请输入支付密码'输入框 → 输出pause，reason写"涉及支付密码，需要用户亲自操作"

## 坐标使用
- 直接使用元素提供的百分比坐标
- 不要凭空猜测坐标，必须使用界面中存在的元素坐标`;
  }

  /**
   * 构建用户Prompt
   */
  private buildUserPrompt(session: TaskSession, uiEvent: UiEventMessage): string {
    const uiText = UiFormatter.formatForAi(uiEvent);
    const historyText = UiFormatter.formatHistory(session.history);
    
    return `${uiText}

用户目标: ${session.goal}

历史动作:
${historyText}`;
  }

  /**
   * 重置指定session的重试计数
   */
  resetRetryCount(sessionId: string): void {
    this.retryCount.delete(sessionId);
  }

  /**
   * 获取session当前重试次数
   */
  private getRetryCount(sessionId: string): number {
    return this.retryCount.get(sessionId) || 0;
  }

  /**
   * 增加重试计数
   */
  private incrementRetryCount(sessionId: string): void {
    const current = this.getRetryCount(sessionId);
    this.retryCount.set(sessionId, current + 1);
  }

  /**
   * 计算退避延迟时间（指数退避 + 抖动）
   */
  private calculateBackoffDelay(attempt: number): number {
    const baseDelay = config.aiRetryBaseDelay;
    const maxDelay = config.aiRetryMaxDelay;
    const multiplier = config.aiRetryBackoffMultiplier;
    
    // 指数退避: baseDelay * (multiplier ^ attempt)
    const exponentialDelay = baseDelay * Math.pow(multiplier, attempt);
    
    // 添加随机抖动（±20%）防止重试风暴
    const jitter = 0.8 + Math.random() * 0.4;
    
    // 确保不超过最大延迟
    return Math.min(exponentialDelay * jitter, maxDelay);
  }

  /**
   * 判断错误是否可重试
   */
  private isRetryableError(error: Error): boolean {
    const retryableErrors = [
      'ECONNRESET',
      'ETIMEDOUT',
      'ECONNREFUSED',
      'ENOTFOUND',
      'EAI_AGAIN',
      'network error',
      'timeout',
      'rate limit',
      '429',
      '503',
      '502',
      '504',
    ];
    
    const errorMessage = error.message.toLowerCase();
    
    return retryableErrors.some(pattern => 
      errorMessage.includes(pattern.toLowerCase())
    );
  }

  /**
   * 延时函数
   */
  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  /**
   * 调用AI模型进行决策（带重试机制）
   * 返回：决策 + 实际Token消耗
   */
  async decide(
    session: TaskSession, 
    uiEvent: UiEventMessage
  ): Promise<{ decision: AiDecision; inputTokens: number; outputTokens: number }> {
    const userPrompt = this.buildUserPrompt(session, uiEvent);
    
    logger.debug('AI deciding', { 
      session_id: session.session_id, 
      step: session.history.length + 1,
      retry_count: this.getRetryCount(session.session_id)
    });

    try {
      // 检查是否处于加载状态
      const loadingCheck = UiFormatter.isLoadingState(uiEvent);
      if (loadingCheck.isLoading) {
        logger.info('Page loading detected, auto-wait', { reason: loadingCheck.reason });
        // 重置重试计数（因为这是预期行为，不是错误）
        this.resetRetryCount(session.session_id);
        return {
          decision: {
            thought: `页面正在加载: ${loadingCheck.reason}`,
            action: 'wait',
            reason: '等待页面加载完成',
            expect: '1秒后页面加载完成',
            params: { ms: 1000 },
          },
          inputTokens: 0,
          outputTokens: 0,
        };
      }

      // 检查敏感操作
      const sensitiveCheck = UiFormatter.detectSensitiveOperations(uiEvent);
      if (sensitiveCheck.isSensitive) {
        logger.info('Sensitive operation detected, requesting pause', { 
          reason: sensitiveCheck.reason 
        });
        // 重置重试计数
        this.resetRetryCount(session.session_id);
        return {
          decision: {
            thought: `检测到敏感操作: ${sensitiveCheck.reason}。根据安全规则，需要用户亲自确认。`,
            action: 'pause',
            reason: sensitiveCheck.reason!,
            expect: '用户手动完成操作后点击继续',
          },
          inputTokens: 0,
          outputTokens: 0,
        };
      }

      // 调用LLM API（带重试）
      const result = await this.callLlmWithRetry(
        this.systemPrompt, 
        userPrompt,
        session.session_id
      );
      
      // 成功时重置重试计数
      this.resetRetryCount(session.session_id);
      
      logger.debug('AI decision', { 
        session_id: session.session_id,
        action: result.decision.action,
        target_id: result.decision.target?.element_id,
        inputTokens: result.inputTokens,
        outputTokens: result.outputTokens
      });

      return result;
    } catch (error) {
      const err = error as Error;
      const currentRetry = this.getRetryCount(session.session_id);
      
      logger.error('AI decision failed after all retries', err, { 
        session_id: session.session_id,
        retry_count: currentRetry,
        max_attempts: config.aiRetryMaxAttempts
      });
      
      // 重置重试计数
      this.resetRetryCount(session.session_id);
      
      // 返回一个安全的默认决策
      return {
        decision: {
          thought: `AI决策出错（已重试${currentRetry}次）: ${err.message}。等待后重试。`,
          action: 'wait',
          reason: 'AI服务暂时不可用，等待重试',
          expect: '等待后系统会自动重试',
          params: { ms: 3000 },
        },
        inputTokens: 0,
        outputTokens: 0,
      };
    }
  }

  /**
   * 带重试机制的LLM调用
   */
  private async callLlmWithRetry(
    systemPrompt: string, 
    userPrompt: string,
    sessionId: string
  ): Promise<{ decision: AiDecision; inputTokens: number; outputTokens: number }> {
    const maxAttempts = config.aiRetryMaxAttempts;
    let lastError: Error | null = null;

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        logger.debug('LLM API call attempt', { 
          session_id: sessionId, 
          attempt: attempt + 1, 
          max_attempts: maxAttempts 
        });
        
        const result = await this.callLlm(systemPrompt, userPrompt);
        
        // 成功时记录日志
        if (attempt > 0) {
          logger.info('LLM API call succeeded after retry', {
            session_id: sessionId,
            attempts: attempt + 1
          });
        }
        
        return result;
      } catch (error) {
        lastError = error as Error;
        
        // 判断是否应该重试
        if (!this.isRetryableError(lastError)) {
          logger.warn('Non-retryable error from LLM API', {
            session_id: sessionId,
            error: lastError.message
          });
          throw lastError; // 不可重试的错误，直接抛出
        }
        
        // 判断是否还有重试机会
        if (attempt < maxAttempts - 1) {
          const delay = this.calculateBackoffDelay(attempt);
          this.incrementRetryCount(sessionId);
          
          logger.warn('LLM API call failed, retrying', {
            session_id: sessionId,
            attempt: attempt + 1,
            max_attempts: maxAttempts,
            delay_ms: Math.round(delay),
            error: lastError.message
          });
          
          await this.sleep(delay);
        }
      }
    }

    // 所有重试都失败了
    throw lastError || new Error('LLM API call failed after all retries');
  }

  /**
   * 调用LLM API（实际请求）
   */
  private async callLlm(
    systemPrompt: string, 
    userPrompt: string
  ): Promise<{ decision: AiDecision; inputTokens: number; outputTokens: number }> {
    const apiUrl = config.aiApiUrl || 'https://api.moonshot.cn/v1/chat/completions';
    const apiKey = config.aiApiKey;
    
    if (!apiKey) {
      throw new Error('AI_API_KEY not configured');
    }

    // 构建请求体
    const requestBody = {
      model: config.aiModel,
      messages: [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userPrompt },
      ],
      max_tokens: config.aiMaxTokens,
      temperature: config.aiTemperature,
    };

    let response: Response;
    
    try {
      response = await fetch(apiUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${apiKey}`,
        },
        body: JSON.stringify(requestBody),
      });
    } catch (networkError) {
      // 网络层错误（DNS、连接超时等）
      const err = networkError as Error;
      throw new Error(`Network error: ${err.message}`);
    }

    if (!response.ok) {
      const errorText = await response.text();
      
      // 根据HTTP状态码提供更详细的错误信息
      let errorMessage = `LLM API error: ${response.status}`;
      
      if (response.status === 401) {
        errorMessage = 'API认证失败：请检查AI_API_KEY是否正确';
      } else if (response.status === 429) {
        errorMessage = 'API速率限制：请求过于频繁，请稍后重试';
      } else if (response.status === 503) {
        errorMessage = 'API服务暂时不可用（503），请稍后重试';
      } else if (response.status >= 500) {
        errorMessage = `API服务器错误 (${response.status})，请稍后重试`;
      }
      
      throw new Error(`${errorMessage} - ${errorText}`);
    }

    let data: {
      choices?: Array<{ message?: { content?: string } }>;
      usage?: { prompt_tokens: number; completion_tokens: number; total_tokens: number };
    };
    
    try {
      data = await response.json() as typeof data;
    } catch (parseError) {
      throw new Error('Failed to parse API response: invalid JSON');
    }
    
    const content = data.choices?.[0]?.message?.content;
    
    if (!content) {
      throw new Error('Empty response from LLM: no content in choices');
    }

    let decision: AiDecision;
    
    try {
      decision = this.parseDecision(content);
    } catch (parseError) {
      throw new Error(`Failed to parse AI decision: ${(parseError as Error).message}`);
    }
    
    // 获取实际Token消耗
    const inputTokens = data.usage?.prompt_tokens || this.estimateTokens(systemPrompt + userPrompt);
    const outputTokens = data.usage?.completion_tokens || this.estimateTokens(content);

    return { decision, inputTokens, outputTokens };
  }

  /**
   * 估算Token数（用于API未返回usage时兜底）
   * 简单估算：1 token ≈ 4个字符（中文）或 0.75个单词（英文）
   */
  private estimateTokens(text: string): number {
    // 中英文字符都按平均4字节算
    return Math.ceil(text.length / 4);
  }

  /**
   * 解析AI返回的决策
   */
  private parseDecision(content: string): AiDecision {
    try {
      // 尝试提取JSON（AI可能返回带markdown格式的JSON）
      const jsonMatch = content.match(/\{[\s\S]*\}/);
      if (jsonMatch) {
        content = jsonMatch[0];
      }
      
      const parsed = JSON.parse(content);
      
      return {
        thought: parsed.thought || '',
        action: parsed.action || 'wait',
        target: parsed.target,
        text: parsed.text,
        reason: parsed.reason || 'AI决策',
        expect: parsed.expect || '',
        params: parsed.params,
      };
    } catch (error) {
      logger.error('Failed to parse AI decision', error, { content });
      throw new Error('Invalid AI response format');
    }
  }

  /**
   * 将AI决策转换为动作指令
   */
  decisionToCommand(decision: AiDecision): ActionCommandMessage {
    const command: ActionCommandMessage = {
      type: 'action',
      action: decision.action as ActionCommandMessage['action'],
      reason: decision.reason,
      expect: decision.expect,
    };

    if (decision.target) {
      command.target = {
        element_id: decision.target.element_id,
        center: decision.target.center,
      };
    }

    if (decision.text) {
      command.text = decision.text;
    }

    if (decision.params?.direction) {
      command.direction = decision.params.direction;
    }

    if (decision.params?.ms) {
      command.ms = decision.params.ms;
    }

    return command;
  }
}

// 导出单例
export const aiCore = new AiCore();
