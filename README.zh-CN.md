<div align="center">

<img src="apps/web/public/favicon.svg" alt="Melora Logo" width="88" height="88" />

# 乐屿 · Melora

**灵动、纯粹的私人音乐世界**<br />
现代化自托管音乐流媒体平台 · 原生独立 Android 高保真音乐播放器

[![Release](https://img.shields.io/badge/Release-v0.1.0-3567e8?style=flat-square)](https://github.com/Raving4934/Melora/releases)
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

**乐屿（Melora）** 是一款追求极致细节与纯净体验的双核音乐软件系统，旨在为音乐爱好者提供自由、高品质、无干扰的听音体验。

- **软件定位**：遵循严格的**“技术中立”原则**。本仓库公开的源代码与自动化 CI 构建产物均为**纯净中立版本**——不存储、不分发任何第三方版权音频文件，亦不内置针对任何商业平台的未授权直链破解逻辑。
- **开箱状态**：开箱即是一套顶级的本地离线高保真音乐播放器（Android 端）与私有自建音频流媒体服务（Web / NAS 端）；
- **扩展生态**：通过内置的轻量安全 JavaScript 沙箱，支持用户自由导入第三方开放标准音源扩展脚本（兼容 LX 规范），实现个性化的音频索引与流式播放。

---

## 🌟 核心特性一览

### 📱 原生 Android 客户端
- **匠心界面与流体微动效**：基于 Jetpack Compose 构建，物理弹簧阻尼抽屉、多模态全屏播放页（封面/黑胶、全屏歌词流、多维音频信息卡），界面元素严格锚定 16dp 与 28.5dp 几何对齐公理；
- **双模自适应微光音质徽标**：`HR / SQ / HQ / 128K` 统一为通透纯净的无背景细描边设计，在深色动态封面背景与浅色常态卡片间自动计算饱和度微光，清晰舒展、毫不刺眼；
- **智能长歌词自适应跑马灯**：微缩歌词高亮行搭载 `basicMarquee` 智能平滑滚动，长句与双语翻译无缝滚动展示，彻底告别粗暴省略号，且锁定固定行高杜绝任何界面抖动；
- **本地高保真音乐中枢**：全盘音频极速扫描、标签自动容错与缺失元数据补全、内嵌专辑封面/歌词无缝提取，支持多维极速排序与批量安全管理，断网也能优雅畅听；
- **毫秒级流式竞速搜索**：全新流式竞速请求管线，首个可用音源毫秒级（100~200ms）极速渲染上屏，多源异步并发汇入，各平台附带专属淡色识别点；
- **开放音源沙箱容器**：兼容 `.js` 脚本，QuickJS 高性能并发池调度，音质请求严格防静默降级。

### 🖥️ Web / NAS 私有云服务
- **高性能自托管服务端**：Go 1.25 核心后端，超轻内存开销与毫秒级 API 响应，内置安全反 SSRF 网关机制；
- **现代 Web 串流体验**：基于 React 19 + TypeScript，响应式布局适配桌面大屏、iPad 与移动浏览器端；
- **全生态极速部署**：
  - **飞牛私有云 (fnOS)**：提供架构专属 `.fpk` 一键安装包，完美融入 NAS 应用中心；
  - **Docker 容器化**：官方轻量级多架构 Docker 镜像，`docker-compose` 单文件一键拉起；
- **多端一致的扩展协议**：服务端独立会话池解析第三方源扩展，保证家庭局域网与外网私有流媒体的一致性体验。

---

## 📊 双端产品形态对比

| 维度 / 功能 | 📱 原生 Android 客户端 | 🖥️ Web / NAS 服务端 |
| :--- | :--- | :--- |
| **支持环境** | Android 8.0+（仅 `arm64-v8a`） | Linux / 飞牛 fnOS / Docker / 私有云 |
| **核心技术栈** | Kotlin + Jetpack Compose + Media3 | Go + SQLite + React 19 + TypeScript |
| **运行依赖** | **完全独立**（无需任何服务器，离线即用） | **自建私有服务**（浏览器即开即用） |
| **本地音频引擎** | 原生 Android Media3 引擎，锁屏/通知栏完整控制 | 浏览器原生 Web Audio / HTML5 媒体引擎 |
| **元数据与标签** | 本地 MP3 / FLAC / M4A 元数据与内嵌封面读写 | 导出音频自动安全嵌入 ID3v2 / Vorbis Tags |
| **扩展支持** | QuickJS 原生多线程并发执行池 | 独立受控 Worker 子进程安全会话池 |

---

## 音源边界与公开构建

未配置音源时仍可浏览搜索、排行榜、歌单及听书元数据；新的在线音频播放和下载需要前往 **设置 → 自定义源**，导入并启用用户选择的脚本。脚本初始化中不按“无源”处理。

- 安卓本地文件、已下载文件和完整音频缓存仍可使用；旧的部分缓存不能绕过脚本发起新的音频网络请求。
- 应用不再含隐藏解锁手势、内置直链解析器。私下维护的解析脚本不在本仓库，也不随 APK、Docker、FPK 打包或自动下载。
- 安卓在设备上执行脚本，不依赖 NAS；Web/NAS 在自建服务端执行用户脚本。请求 HR 不代表实际返回 24bit，平台、歌曲版本和实际音质需要分别识别。
- 演示模式须显式设置 `MELORA_DEMO_MODE=1`；没有音源不会自动切换演示模式。
- Web/NAS 的公网地址、重定向与响应限额检查属于服务端网络代理策略，不能将其等同于安卓 HTTP 宿主的全部行为。运行时展示远程封面/歌词、保存用户选择的文件，不等于发行包附带第三方商业曲库；组件许可见 `THIRD_PARTY_NOTICES.md`。

### 本次 0.1.0 新发布起点

安卓和 Web/NAS 分别使用 `android-v0.1.0`、`v0.1.0` 标签。安卓保留现有正式签名，**versionCode 升至 6**，不随显示版本号回退。旧版只按版本名检查更新，本次重设为 0.1.0 时可能需要手动覆盖安装一次，勿卸载或清数据；后续正常递增版本仍走常规更新流程。

## 🚀 快速开始与使用指南

### 📱 Android 客户端

#### 1. 下载安装
前往 [GitHub Releases](https://github.com/Raving4934/Melora/releases) 页面，下载最新的 `app-release.apk` 安装至 Android 设备。

#### 2. 本地音乐播放
安装后即可直接使用：
- 进入**本地歌曲**页面，点击“扫描媒体库”或授予存储权限；
- 支持自动扫描全机音频、自动过滤小于 60s 或 1MB 的提示音，秒速整理歌手与专辑分类；
- 离线状态下可完整享受高保真音频解码、内嵌封面展示与桌面歌词。

#### 3. 扩展支持
- 本软件全面**兼容 LX 音源规范**，用户自管扩展脚本运行于受限的本地隔离沙箱中。

---

### 🖥️ Web / NAS 私有云部署

#### 方案 A：飞牛私有云 (fnOS) 一键安装（推荐）
1. 前往 [GitHub Releases](https://github.com/Raving4934/Melora/releases) 下载适合您 NAS CPU 架构的安装包：
   - 常见 x86 架构：`melora-0.1.0-linux-amd64.fpk`
   - ARM 架构：`melora-0.1.0-linux-arm64.fpk`
2. 登录飞牛桌面，打开 **应用中心** → **手动安装** → 上传 `.fpk` 文件完成安装；
3. 详细存储权限与路径规划请参阅 [fnOS 部署文档](docs/fnos.md)。

#### 方案 B：Docker Compose 极速部署
通过 Docker 可快速在群晖、威联通、TrueNAS、Unraid 或任意 Linux VPS 上搭建专属流媒体台：

```bash
# 1. 复制环境变量示例文件
cp deploy/.env.example .env

# 2. 编辑 .env，设置后台管理密码（至少 8 位）
# MELORA_ADMIN_PASSWORD=your_secure_password

# 3. 拉取最新镜像并后台启动
docker compose pull
docker compose up -d
```

服务默认监听 `127.0.0.1:3780`。强烈建议在前端搭配 Nginx、Caddy 或 NPM 开启 HTTPS 反向代理，以获得更安全的播放与 PWA 体验。

---

## 🛡️ 安全沙箱与运行边界

乐屿服务端与客户端均内置了严格的受限 JavaScript 运行环境：
- **无宿主权限**：脚本无法直接访问文件系统、系统环境变量、原生 Shell、DOM 或网络原始 Socket；
- **网络访问受限**：只能通过受限的专用 HTTP 桥接向指定公共网络接口发起请求，拒绝任何针对本地局域网（SSRF 保护）的非法探测；
- **执行边界**：Web/NAS 扩展在受执行预算约束的 worker 中运行；安卓 HTTP 桥限制请求时间与响应大小。仅导入可信扩展，安卓脚本环境并不保证隔离任意同步 JavaScript 死循环。

---

## 📁 代码仓库结构

```
.
├── android/                 # 原生 Android 客户端源码 (Kotlin + Jetpack Compose)
│   ├── app/                 # 主工程模块 (UI 视图、播放控制器、Media3 适配)
│   └── quickjs-android/     # 高性能轻量 QuickJS 原生 C++ 绑定引擎
├── apps/
│   ├── server/              # 核心服务端源码 (Go 1.25，高并发流媒体核心)
│   └── web/                 # 现代化前端界面源码 (React 19 + TypeScript + Vite)
├── packaging/
│   └── fpk/                 # 飞牛私有云 (fnOS) 打包构建配置与图标规范
├── deploy/                  # Docker 与 Docker-Compose 部署资产模板
└── docs/                    # 详细架构与第三方规范技术文档
```

---


### 从公开源码构建

- Web/服务端：`npm ci` 后执行 `npm run build`，Go 代码在 `apps/server`。
- 安卓：使用 JDK 17，以及 `android/app/build.gradle.kts` 声明的 SDK/NDK，再执行 `cd android && ./gradlew :app:assembleDebug`；Debug 与正式版包名分离。
- 正式签名通过环境变量 `MELORA_KEYSTORE_PATH`、`MELORA_KEYSTORE_PASSWORD`、`MELORA_KEY_ALIAS`、`MELORA_KEY_PASSWORD` 注入，CI 使用仓库 Secrets；禁止提交密钥和这些值。
- Android 与 Web/NAS 构建工作流独立。构建不需要私有音源脚本，也不会将其打包。

## 💖 致谢与开源鸣谢

- [lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop)：本项目部分公开目录数据结构与扩展源协议规范衍生自落雪音乐（遵循 Apache-2.0 许可证开源），特此向原作者及开源社区致以敬意。

---

## ⚖️ 技术中立与免责声明

1. **技术探索定位**：乐屿（Melora）是一个面向技术学习与个人媒体管理的开源探索项目，本质为一个**通用的多模态音频播放器框架与自建服务底座**；
2. **零版权内容提供**：本项目的公开仓库源代码与预编译产物中**不包含、不托管、不分发任何音乐音频实体、歌词或第三方受版权保护的商业素材**；
3. **非商业与合规限制**：本仓库代码遵循专用的非商业开源许可证（[LICENSE](LICENSE)）。严格禁止任何形式的商业化倒卖、捆绑收费广告，严禁在任何二次衍生版本中私自捆绑内置针对商业平台的破解源，以维护纯净中立的开源生态；
4. **版权保护与下线**：若任何权利人认为本项目代码或文档侵犯了您的合法权益，请查阅完整声明并与我们联系，我们将在核实后依法处理；
5. 详细法律与权利边界条款请阅读项目根目录下的 **[DISCLAIMER.md](DISCLAIMER.md)**。

---

<div align="center">
  <sub>Made with passion for music lovers.</sub>
</div>
