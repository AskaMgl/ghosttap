# GhostTap Server

Node.js WebSocket 服务端。

## 安装

```bash
npm install
```

## 配置

```bash
cp .env.example .env
# 编辑 .env
```

## 开发

```bash
npm run dev
```

## 生产

```bash
npm run build
npm start
```

## Docker

```bash
docker build -t ghosttap-server .
docker run -p 8080:8080 ghosttap-server
```
