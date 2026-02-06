import { config, validateConfig } from './config';
import { logger } from './logger';
import { wsGateway } from './websocket-gateway';
import { httpApi } from './http-api';
import { dbManager } from './database';

/**
 * GhostTap Server 入口
 */
async function main() {
  logger.info('Starting GhostTap Server...');

  // 验证配置
  try {
    validateConfig();
  } catch (error) {
    logger.error('Config validation failed', error);
    process.exit(1);
  }

  // 启动WebSocket服务器
  wsGateway.start(config.port, config.host);

  // 启动HTTP API服务器（使用不同端口）
  httpApi.start(config.port + 1, config.host);

  logger.info('GhostTap Server started successfully');
  logger.info(`  - WebSocket: ws://${config.host}:${config.port}`);
  logger.info(`  - HTTP API: http://${config.host}:${config.port + 1}`);
  logger.info(`  - Database: ${config.dbPath}`);
  logger.info(`  - AI Model: ${config.aiModel}`);

  // 优雅关闭
  process.on('SIGINT', () => {
    logger.info('Shutting down...');
    wsGateway.stop();
    httpApi.stop();
    dbManager.close();
    process.exit(0);
  });

  process.on('SIGTERM', () => {
    logger.info('Shutting down...');
    wsGateway.stop();
    httpApi.stop();
    dbManager.close();
    process.exit(0);
  });
}

main().catch(error => {
  logger.error('Server failed to start', error);
  process.exit(1);
});