import { config } from './config';

type LogLevel = 'debug' | 'info' | 'warn' | 'error';

const LOG_LEVELS: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
};

class Logger {
  private level: number;

  constructor() {
    this.level = LOG_LEVELS[config.logLevel];
  }

  private shouldLog(level: LogLevel): boolean {
    return LOG_LEVELS[level] >= this.level;
  }

  private format(level: LogLevel, message: string, meta?: Record<string, any>): string {
    const timestamp = new Date().toISOString();
    const metaStr = meta ? ' ' + JSON.stringify(meta) : '';
    return `[${timestamp}] [${level.toUpperCase()}] ${message}${metaStr}`;
  }

  debug(message: string, meta?: Record<string, any>): void {
    if (this.shouldLog('debug')) {
      console.log(this.format('debug', message, meta));
    }
  }

  info(message: string, meta?: Record<string, any>): void {
    if (this.shouldLog('info')) {
      console.log(this.format('info', message, meta));
    }
  }

  warn(message: string, meta?: Record<string, any>): void {
    if (this.shouldLog('warn')) {
      console.warn(this.format('warn', message, meta));
    }
  }

  error(message: string, error?: Error | unknown, meta?: Record<string, any>): void {
    if (this.shouldLog('error')) {
      const errorMeta = error instanceof Error 
        ? { ...meta, error: error.message, stack: error.stack }
        : meta;
      console.error(this.format('error', message, errorMeta));
    }
  }
}

export const logger = new Logger();