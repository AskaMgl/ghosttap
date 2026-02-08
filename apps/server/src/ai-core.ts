import { UiEventMessage, AiDecision, ActionCommandMessage, SessionContext } from './types';
import { config } from './config';
import { logger } from './logger';
import { UiFormatter } from './formatter';
import { sessionManager } from './session-manager';

/**
 * AI Core - 调用LLM进行决策
 * 
 * 职责：根据当前UI状态和任务目标，选择最合适的下一个动作
 * 
 * v3.12 更新:
 * - 更新 System Prompt：移除 expect，更新敏感操作检测规则，添加软键盘处理规则
 * - 移除 element_id 相关逻辑
 * - 新增 home() 和 launch_app(package_name) 动作
 * - swipe 动作支持可选的 target.center、distance、duration_ms 参数
 * - 决策输出不再包含 expect 和 element_id
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
   * v3.12: 移除 expect，更新敏感操作检测规则，添加软键盘处理规则
   */
  private buildSystemPrompt(): string {
    return `你是一个手机自动化助手，负责控制用户的Android手机完成指定任务。

## 你的职责
1. 分析当前界面状态
2. 根据用户目标选择最合适的动作
3. 以JSON格式返回决策

## 输入格式
屏幕: {width}x{height} ({package})
键盘: 可见, 占屏幕 {height}%（y > {threshold}% 的区域被遮挡）

[element_id] type "text" @ (x%, y%) [available_actions]
...

共 {count} 个可交互元素

用户目标: {goal}

历史动作:
- [{timestamp}] {action} ({reason})

## 输出格式
必须返回有效的JSON:
{
  "thought": "你的分析过程，包括当前界面状态、目标进度、下一步计划",
  "action": "动作名称",
  "target": {                         // click/input 动作时必填
    "center": [x百分比, y百分比]
  },
  "text": "输入文本",                  // input 动作时必填
  "direction": "up/down/left/right",  // swipe 动作时必填
  "distance": 30,                     // swipe 距离百分比（可选，默认30）
  "duration_ms": 300,                 // swipe 时长毫秒（可选，默认300）
  "package_name": "com.example.app",  // launch_app 动作时必填
  "wait_ms": 1000,                    // wait 动作时必填（毫秒）
  "reason": "选择这个动作的原因"
}

## 可用动作
- click: 点击指定位置（需要target.center）
- input: 在指定位置输入文字（需要target + text）
- swipe: 滑动屏幕（需要direction: up/down/left/right；可选 target.center 指定起点百分比坐标，默认屏幕中心；可选 distance 指定滑动距离百分比，默认30；可选 duration_ms 指定滑动时长毫秒，默认300）
- back: 返回上一级
- home: 回到桌面（映射 GLOBAL_ACTION_HOME）
- launch_app: 启动指定APP（需要package_name，如"com.taobao.taobao"；无需先回桌面，可直接从任意界面拉起目标APP）
- wait: 等待指定毫秒后重新获取UI（需要wait_ms）
- pause: 暂停任务，请求人工接管
- done: 任务完成（需要reason说明结果）
- fail: 任务无法完成（需要reason说明原因）

> done/fail 是终止指令，输出后任务立即结束，不会再收到新的UI。

## 规则
1. 所有坐标使用百分比 [0-100, 0-100]
2. input 动作会自动聚焦目标输入框，无需先单独 click
3. **手机端执行完全依赖 center 坐标定位，务必确保 center 坐标准确**
4. 找不到目标时尝试滑动或返回
5. 连续3次UI无实质变化且输出相同动作时，输出 fail("操作无进展")
6. 遇到弹窗优先处理弹窗
7. **软键盘处理**：当键盘可见时，屏幕底部 keyboard_height% 区域被键盘遮挡。如果目标元素的 y 坐标落在被遮挡区域内（y% > 100 - keyboard_height%），应先执行 back() 收起键盘，再操作目标元素

## 页面加载等待策略
当界面显示以下状态时，必须等待而非立即执行：
- "加载中"、"正在加载"等文字提示
- 转圈动画、骨架屏
- 元素明显不全（如只有标题栏，内容区空白）
- 网络请求提示

**正确做法**：
\`\`\`json
{
  "thought": "页面显示'加载中'转圈，内容尚未加载完成",
  "action": "wait",
  "wait_ms": 1000,
  "reason": "等待页面加载完成"
}
\`\`\`

## ⚠️ 敏感操作检测（第二层：AI 软检测）
> 注意：系统已有第一层硬编码关键词检测（"支付"、"密码"等），会在你被调用之前自动拦截。
> 你的职责是覆盖第一层无法捕获的**变体表述和上下文敏感场景**。

当界面出现以下情况时，必须输出 pause：
1. 涉及资金操作（转账、充值、购买确认等）
2. 涉及身份验证（人脸识别、短信验证码确认等）
3. 涉及不可逆操作（删除账号、解除绑定等）
4. 其他需要用户亲自确认的操作

**示例**：
\`\`\`json
{
  "thought": "界面出现'确认转账给张三 ¥500.00'，涉及资金操作",
  "action": "pause",
  "reason": "检测到转账确认，需要用户亲自确认"
}
\`\`\`

## WebView 快速失败
AI 看到单一 WebView 节点且无子元素时，直接输出 fail("当前页面为 WebView，无法采集内容")，快速失败优于无效盲点尝试。

## 已知限制：系统弹窗与覆盖层
- 权限弹窗（package 变为 com.android.packageinstaller 等）：根据任务需要选择"允许"或"拒绝"
- 来电覆盖（package 变为电话 APP）：输出 wait，等待用户处理完来电
- 通知面板（package 变为 com.android.systemui）：输出 back() 收起通知面板`;
  }

  /**
   * 构建用户Prompt
   */
  private buildUserPrompt(session: SessionContext, uiEvent: UiEventMessage): string {
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
   * v3.12: 第一层敏感词检测在调用前由 WebSocketGateway 执行
   * 返回：决策 + 实际Token消耗
   */
  async decide(
    session: SessionContext, 
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
        this.resetRetryCount(session.session_id);
        return {
          decision: {
            thought: `页面正在加载: ${loadingCheck.reason}`,
            action: 'wait',
            wait_ms: 1000,
            reason: '等待页面加载完成',
          },
          inputTokens: 0,
          outputTokens: 0,
        };
      }

      // v3.12: 第二层敏感操作检测（AI软检测）
      // 第一层硬检测由 WebSocketGateway 在调用 decide 之前执行
      const sensitiveCheck = UiFormatter.detectSensitiveOperations(uiEvent);
      if (sensitiveCheck.isSensitive) {
        // 检查是否是第一层已经拦截过的（通过reason判断）
        const isFirstLayer = sensitiveCheck.reason?.includes('检测到敏感操作:') ||
                            sensitiveCheck.reason?.includes('检测到支付金额:');
        if (!isFirstLayer) {
          logger.info('Sensitive operation detected by AI (second layer)', { 
            reason: sensitiveCheck.reason 
          });
          this.resetRetryCount(session.session_id);
          return {
            decision: {
              thought: `检测到敏感操作: ${sensitiveCheck.reason}。根据安全规则，需要用户亲自确认。`,
              action: 'pause',
              reason: sensitiveCheck.reason!,
            },
            inputTokens: 0,
            outputTokens: 0,
          };
        }
      }

      // 调用LLM API（带重试）
      const result = await this.callLlmWithRetry(
        this.systemPrompt, 
        userPrompt,
        session.session_id
      );
      
      // 成功时重置重试计数和AI失败计数
      this.resetRetryCount(session.session_id);
      sessionManager.resetAiFailureCount(session.session_id);  // v3.13
      
      logger.debug('AI decision', { 
        session_id: session.session_id,
        action: result.decision.action,
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
          wait_ms: 3000,
          reason: 'AI服务暂时不可用，等待重试',
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
          'User-Agent': 'KimiCLI/0.77',
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
   * v3.12: 移除 expect 和 element_id 解析
   */
  private parseDecision(content: string): AiDecision {
    try {
      // 尝试提取JSON（AI可能返回带markdown格式的JSON）
      const jsonMatch = content.match(/\{[\s\S]*\}/);
      if (jsonMatch) {
        content = jsonMatch[0];
      }
      
      const parsed = JSON.parse(content);
      
      const decision: AiDecision = {
        thought: parsed.thought || '',
        action: parsed.action || 'wait',
        reason: parsed.reason || 'AI决策',
      };

      // v3.12: 只解析 center 坐标，不解析 element_id
      if (parsed.target?.center) {
        decision.target = {
          center: parsed.target.center,
        };
      }

      // 可选参数
      if (parsed.text) {
        decision.text = parsed.text;
      }
      if (parsed.direction) {
        decision.direction = parsed.direction;
      }
      if (parsed.distance !== undefined) {
        decision.distance = parsed.distance;
      }
      if (parsed.duration_ms !== undefined) {
        decision.duration_ms = parsed.duration_ms;
      }
      if (parsed.package_name) {
        decision.package_name = parsed.package_name;
      }
      if (parsed.wait_ms !== undefined) {
        decision.wait_ms = parsed.wait_ms;
      }

      return decision;
    } catch (error) {
      logger.error('Failed to parse AI decision', error, { content });
      throw new Error('Invalid AI response format');
    }
  }

  /**
   * 将AI决策转换为动作指令
   * v3.12: 移除 expect 和 element_id，支持新的动作参数
   */
  decisionToCommand(decision: AiDecision): ActionCommandMessage {
    const command: ActionCommandMessage = {
      type: 'action',
      action: decision.action as ActionCommandMessage['action'],
      reason: decision.reason,
    };

    // v3.12: 只传递 center，不传递 element_id
    if (decision.target?.center) {
      command.target = {
        center: decision.target.center,
      };
    }

    if (decision.text) {
      command.text = decision.text;
    }

    if (decision.direction) {
      command.direction = decision.direction;
    }

    if (decision.distance !== undefined) {
      command.distance = decision.distance;
    }

    if (decision.duration_ms !== undefined) {
      command.duration_ms = decision.duration_ms;
    }

    if (decision.package_name) {
      command.package_name = decision.package_name;
    }

    if (decision.wait_ms !== undefined) {
      command.wait_ms = decision.wait_ms;
    }

    return command;
  }
}

// 导出单例
export const aiCore = new AiCore();
