# 👻 GhostTap

AI 驱动的 Android 远程控制。让 AI 成为你的手机替身。

[English](README_EN.md) | 简体中文

## ✨ 特性

- 🤖 **AI 自动化** - 自然语言描述任务，AI 自动完成
- 📱 **无障碍操作** - 基于 Android AccessibilityService，无需 Root
- 🔒 **隐私优先** - 敏感操作自动暂停，数据不留存
- 🌐 **开源可审计** - 服务端代码开放，可自建服务器
- ⚡ **低延迟** - WebSocket 实时通信，响应 <100ms

## 🚀 快速开始

### 1. 下载 Android App

[下载 APK](https://github.com/ghosttap/android/releases) 或自行编译

### 2. 获取用户 ID

在 OpenClaw/Feishu 聊天框输入：
```
/get_user_id
```

复制返回的 `user_xxx` ID

### 3. 连接服务端

打开 GhostTap App：
1. 填入 user_id
2. 开启无障碍权限
3. 开启悬浮窗权限
4. 点击"启动服务"

### 4. 开始自动化

对 OpenClaw/Feishu 说：
```
帮我签到
帮我买一杯咖啡
帮我回复微信消息
```

## 📖 文档

- [架构设计](docs/architecture.md)
- [通信协议](docs/protocol.md)
- [安全说明](docs/security.md)
- [自部署指南](docs/self-hosting.md)

## 🏗️ 架构

```
┌─────────────────────────────────────┐
│  用户: "帮我签到"                    │
│  OpenClaw / Feishu / 任意 IM        │
└─────────────┬───────────────────────┘
              │
┌─────────────▼───────────────────────┐
│  GhostTap Server (云端)              │
│  - WebSocket Gateway                 │
│  - AI Core (LLM)                     │
│  - UI Formatter                      │
└─────────────┬───────────────────────┘
              │ WebSocket (wss://)
┌─────────────▼───────────────────────┐
│  GhostTap Android (手机端)           │
│  - Accessibility Service             │
│  - WebSocket Client                  │
│  - Floating Window                   │
└─────────────────────────────────────┘
```

## 🔧 自部署

```bash
# 克隆仓库
git clone https://github.com/ghosttap/ghosttap.git
cd ghosttap

# 启动服务端
cd apps/server
npm install
npm run dev

# 编译 Android App
cd ../android
./gradlew assembleDebug
```

详细指南：[自部署文档](docs/self-hosting.md)

## 🤝 贡献

欢迎 PR 和 Issue！

- [贡献指南](CONTRIBUTING.md)
- [开发文档](docs/development.md)
- [行为准则](CODE_OF_CONDUCT.md)

## 📄 许可

MIT License - 详见 [LICENSE](LICENSE)

## 🙏 致谢

- [OpenClaw](https://github.com/openclaw/openclaw) - AI 助手框架
- [Android Accessibility](https://developer.android.com/guide/topics/ui/accessibility) - Android 无障碍服务

---

**Disclaimer**: GhostTap 是一个自动化工具，请遵守各平台的服务条款。仅用于个人学习和自动化测试，不要用于违反平台规则的操作。
