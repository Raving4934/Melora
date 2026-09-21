<div align="center">

<img src="apps/web/public/favicon.svg" alt="Melora Logo" width="88" height="88" />

# 乐屿 · Melora

自托管音乐流媒体服务与独立 Android 本地音乐播放器

[![Release](https://img.shields.io/badge/Release-v0.1.1-3567e8?style=flat-square)](https://github.com/Raving4934/Melora/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Raving4934/Melora/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Web%20%7C%20Docker%20%7C%20fnOS-blue?style=flat-square)](packaging/fpk)
[![Architecture](https://img.shields.io/badge/Design-Neutral%20Architecture-059669?style=flat-square)](DISCLAIMER.md)
[![React](https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black)](apps/web)
[![Go](https://img.shields.io/badge/Go-1.25-00ADD8?style=flat-square&logo=go&logoColor=white)](apps/server)
[![License](https://img.shields.io/badge/License-Non--Commercial-blue?style=flat-square)](LICENSE)

<p align="center">
  <a href="README.md">English Documentation</a>
  ·
  <a href="LICENSE">开源许可证</a>
  ·
  <a href="DISCLAIMER.md">免责声明与技术中立</a>
</p>

</div>

---

## 📖 项目简介

**乐屿（Melora）** 是一套面向个人与家庭的音乐播放与媒体管理系统。项目包含两个独立的组成部分：

- **Android 客户端**：原生应用，可直接作为本地音乐播放器使用，支持完全离线播放；
- **Web / NAS 服务端**：基于 Go 与 React，便于在私有 NAS（如飞牛 fnOS）或服务器上搭建自托管音乐流媒体服务。

### 基本设计原则

1. **不包含版权媒体与内置解析**：本仓库公开的源代码及发布包不含任何受版权保护的音频文件，也不内置针对任何商业音乐平台的抓取或破解逻辑；
2. **两端彼此独立**：Android 客户端完全独立运行，无需搭建服务端；
3. **支持标准扩展**：提供受限的 JavaScript 沙箱环境，支持用户按需导入兼容 LX 规范的第三方音源扩展脚本。

---

## 🌟 主要功能

### 📱 Android 客户端
- **本地音乐管理**：支持扫描手机本地音频文件，自动过滤短音频与提示音；读取并展示 MP3、FLAC、M4A 等常见格式的 ID3 标签、内嵌封面与歌词；
- **播放与界面**：基于 Jetpack Compose 开发，支持封面/黑胶播放页、双语滚动歌词、桌面浮窗歌词，以及系统通知栏和锁屏媒体控制；
- **原生时序歌词**：全屏与 mini 共用渲染器，支持增强 LRC、Apple/AMLL 风格 TTML 的逐词填充、翻译/音译、对唱元数据及背景人声，按页面设置统一对齐；全屏采用中性黑白灰字内填充；手动浏览后约 3 秒恢复弹性跟随。普通 LRC 保留行级高亮，不估造音节时间。歌词仍来自现有在线/本地内嵌入口，不内置 TTML 曲库或自动匹配服务。
- **音源与搜索**：支持导入自定义扩展脚本（兼容 LX 规范），在本地 QuickJS 沙箱中执行；支持多源并发搜索与流式结果展示。

### 🖥️ Web / NAS 服务端
- **轻量后端**：基于 Go 开发，资源占用低，内置服务端请求安全检查（防 SSRF）；
- **现代化前端**：基于 React 19 + TypeScript 开发，适配桌面端大屏与移动端浏览器；
- **多种部署方式**：提供飞牛私有云 (fnOS) 的 `.fpk` 安装包，以及标准的 Docker / Docker Compose 部署方案；
- **独立扩展沙箱**：在独立工作进程中运行用户导入的音源扩展脚本，供家庭内网或远程串流使用。

---

## 📊 双端形态对比

| 对比项 | 📱 Android 客户端 | 🖥️ Web / NAS 服务端 |
| :--- | :--- | :--- |
| **支持环境** | Android 8.0+（仅 `arm64-v8a`） | Linux / 飞牛 fnOS / Docker 等私有云环境 |
| **技术栈** | Kotlin + Jetpack Compose + Media3 | Go + SQLite + React 19 + TypeScript |
| **运行依赖** | **完全独立**（本地安装即用，无需服务器） | **私有部署**（部署后通过浏览器访问） |
| **音频引擎** | Android Media3 原生媒体框架 | 浏览器 Web Audio / HTML5 Audio |
| **标签与元数据** | 读取与解析本地音频内嵌标签及封面 | 导出音频时写入 ID3v2 / Vorbis 元数据 |
| **扩展运行环境** | 本地集成 QuickJS 沙箱 | 服务端独立 Worker 子进程沙箱 |

---

## 📌 音源配置与使用说明

1. **无音源时的功能**：
   - 未导入任何音源时，仍可正常使用本地播放、查看歌单元数据与排行榜等基础功能；
   - 在线试听与下载需要进入 **设置 → 自定义源** 手动导入并启用第三方脚本。
2. **中立性与版权边界**：
   - 本项目不提供、不维护、不打包任何特定商业平台的音源解析脚本；
   - 运行时展示的远程封面、歌词或用户下载的文件均由用户配置的脚本或外部服务返回，不代表发行包内置了曲库。
3. **音质显示说明**：
   - 音质标识（如 HR、SQ、HQ、128K）基于脚本返回与实际音频流情况展示；标称 HR 不代表所有音轨均为真正的 24-bit 高解析音频。
4. **演示模式说明**：
   - 演示模式需显式配置环境变量 `MELORA_DEMO_MODE=1` 开启，未配置音源不会自动切换为演示模式。
5. **发布通道与升级说明**：
   - Android 与 Web/NAS 使用独立版本标签；当前 Android 基准为 `android-v0.1.1`，Web/NAS 基准为 `v0.1.1`；
   - Android 正式签名保持不变，`versionCode` 已升至 `7`。可直接覆盖安装新版 APK，无需卸载或清空数据。

---

## 🚀 快速上手

### 📱 Android 客户端

1. **下载安装**：前往 [GitHub Releases](https://github.com/Raving4934/Melora/releases) 下载 `melora-android-v0.1.1-arm64-v8a.apk` 并安装。
2. **播放本地音乐**：打开应用进入“本地歌曲”，授予存储权限或点击“扫描媒体库”，即可直接离线播放。
3. **导入音源扩展**：如需在线功能，可在 **设置 → 自定义源** 中导入兼容 LX 规范的 `.js` 脚本。

---

### 🖥️ Web / NAS 服务端部署

#### 方案 A：飞牛私有云 (fnOS) 一键安装（推荐）

1. 前往 [GitHub Releases](https://github.com/Raving4934/Melora/releases) 下载对应架构的安装包：
   - x86-64 设备：`melora-0.1.1-linux-amd64.fpk`
   - ARM64 设备：`melora-0.1.1-linux-arm64.fpk`
2. 打开飞牛桌面端，进入 **应用中心** → **手动安装**，上传 `.fpk` 文件完成安装。

#### 方案 B：Docker Compose 部署

适用于群晖、威联通、TrueNAS、Unraid 或通用 Linux 服务器：

```bash
# 1. 复制环境变量示例文件
cp deploy/.env.example .env

# 2. 编辑 .env 设置管理员密码（建议 8 位以上）
# MELORA_ADMIN_PASSWORD=your_secure_password

# 3. 拉取镜像并启动服务
docker compose pull
docker compose up -d
```

服务默认监听 `127.0.0.1:3780`。生产环境建议通过 Nginx、Caddy 等反向代理配置 HTTPS。

---

## 🛡️ 安全机制与沙箱边界

为了降低第三方脚本的安全风险，乐屿在客户端与服务端均提供了限制运行环境：

- **系统权限隔离**：脚本无法直接访问宿主操作系统的文件系统、环境变量、命令行终端或原生 Socket；
- **网络访问受限**：脚本仅能通过宿主提供的 HTTP 桥发起请求。Web/NAS 服务端对目标地址和重定向进行安全检查，拦截针对本地局域网（SSRF）的非法请求；
- **执行边界提示**：Web/NAS 的脚本在受限子进程中执行；Android 端的网络桥对请求耗时与响应大小做了上限限制。请务必仅导入来源可信的脚本。

---

## 📁 仓库目录结构

```
.
├── android/                 # Android 客户端源码 (Kotlin + Jetpack Compose)
│   ├── app/                 # 应用主工程 (界面、播放逻辑、Media3 适配)
│   └── quickjs-android/     # QuickJS 原生 C++ 绑定模块
├── apps/
│   ├── server/              # 服务端源码 (Go)
│   └── web/                 # Web 前端源码 (React 19 + TypeScript + Vite)
├── packaging/
│   └── fpk/                 # 飞牛私有云 (fnOS) 打包配置与资源
├── deploy/                  # Docker 与 Docker Compose 部署配置文件
└── docs/                    # 详细架构与规范技术文档
```

---

## 🛠️ 从源码构建

### Web 与服务端构建

```bash
# 编译前端
npm ci
npm run build

# 编译 Go 服务端 (位于 apps/server)
cd apps/server
go build -o melora-server main.go
```

### Android 客户端构建

- 环境要求：JDK 17，以及 `android/app/build.gradle.kts` 中指定的 Android SDK 与 NDK。
- 编译 Debug 版本：
  ```bash
  cd android
  ./gradlew :app:assembleDebug
  ```
  > **说明**：Debug 与 Release 版本使用不同的包名以方便并存测试。正式打包签名通过环境变量（`MELORA_KEYSTORE_PATH`、`MELORA_KEYSTORE_PASSWORD`、`MELORA_KEY_ALIAS`、`MELORA_KEY_PASSWORD`）注入，请勿在仓库中硬编码或提交密钥文件。

---

## 💖 致谢与开源参考

- [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop)：本项目的部分目录数据结构与音源扩展协议兼容自落雪音乐（基于 Apache-2.0 许可证开源），感谢原作者及社区的探索与贡献。

---

## ⚖️ 技术中立与免责声明

1. **学习与个人用途**：本项目为技术探索与个人媒体管理开源项目，定位为通用的音频播放工具与私有流媒体服务；
2. **不含版权内容**：公开代码仓库与预编译发布包均不包含、不托管、不分发任何受版权保护的音乐音频、歌词及商业素材；
3. **非商业许可约束**：本仓库遵循专用非商业开源许可证（[LICENSE](LICENSE)）。严禁将本项目用于任何商业牟利、付费打包或捆绑商业破解源；
4. **权利保护与处理**：若任何版权方认为本项目内容存在侵权情形，请查阅完整声明并联系我们，我们将在核实后及时响应处理；
5. 完整法律条款与权责边界请参阅根目录下的 **[DISCLAIMER.md](DISCLAIMER.md)**。

---

<div align="center">
  <sub>Made with passion for music lovers.</sub>
</div>
