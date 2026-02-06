# GhostTap Server Dockerfile
FROM node:20-alpine

WORKDIR /app

# 复制协议包
COPY packages/protocol ./packages/protocol
RUN cd packages/protocol && npm install && npm run build

# 复制服务端代码
COPY apps/server ./apps/server
WORKDIR /app/apps/server

# 安装依赖
RUN npm install

# 构建
RUN npm run build

# 暴露端口
EXPOSE 8080 8081

# 启动
CMD ["npm", "start"]