<div align="center">

<img src="apps/web/public/favicon.svg" alt="Melora Logo" width="84" height="84" />

# 乐屿 · Melora

**灵动、纯粹的私人音乐世界**
现代化自托管音乐流媒体服务 · 独立原生 Android 音乐播放器

[![Release](https://img.shields.io/badge/Release-v0.1.0-3567e8?style=flat-square)](https://github.com/Raving4934/Melora/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Raving4934/Melora/releases?q=android-v)
[![Platform](https://img.shields.io/badge/Platform-Web%20%7C%20Docker%20%7C%20fnOS-blue?style=flat-square)](packaging/fpk)
[![React](https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black)](apps/web)
[![Go](https://img.shields.io/badge/Go-1.25-00ADD8?style=flat-square&logo=go&logoColor=white)](apps/server)
[![Disclaimer](https://img.shields.io/badge/Notice-DISCLAIMER-orange?style=flat-square)](DISCLAIMER.md)

<p align="center">
  <a href="README.md">English</a>
 ·
  <a href="DISCLAIMER.md">免责声明</a>
</p>

</div>

---

## 📊 产品形态对比

| 功能 / 维度 | 📱 原生 Android 客户端 | 🖥️ Web / NAS 服务端 |
| :--- | :--- | :--- |
| **支持平台** | Android 8.0+ | Linux / 飞牛 fnOS / Docker / 私有云 |
| **核心架构** | Kotlin + Jetpack Compose + Media3 | Go + SQLite + React 19 + TypeScript |
| **服务依赖** | **完全独立**（无需运行任何服务端） | **独立服务**（浏览器即开即用） |
| **音频引擎** | 原生 Media3 播放框架，锁屏/通知栏完整联动 | 浏览器原生 Web Audio 引擎 |

---

## 🚀 快速体验与获取

### 📱 Android 移动端安装
无需搭建任何后台服务，下载即可直接使用：
1. 前往 [GitHub Releases](https://github.com/Raving4934/Melora/releases) 页面。

---

### 🖥️ Web / NAS 私有部署

#### 方案 A：飞牛私有云 (fnOS) 一键安装
1. 在 [Releases](https://github.com/Raving4934/Melora/releases) 中下载对应架构的 FPK 安装包：
   - 常见 x86 架构 NAS：`melora-0.1.0-linux-amd64.fpk`
   - ARM 架构设备：`melora-0.1.0-linux-arm64.fpk`
2. 打开飞牛桌面进入 **应用中心** -> **手动安装** -> 上传对应 `.fpk` 文件。
3. 存储挂载与权限声明见 [FPK 包配置](packaging/fpk/config)。

#### 方案 B：Docker 极速部署 (推荐配合反向代理)
使用 `docker-compose` 快速部署 Melora Cloud 在线服务：

```bash
# 1. 复制环境变量示例文件
cp deploy/.env.example .env

# 2. 编辑 .env，配置管理员密码（至少 8 位）
# MELORA_ADMIN_PASSWORD=your_secure_password

# 3. 拉取最新镜像并后台启动
docker compose pull
docker compose up -d
```

启动后服务默认监听 `127.0.0.1:3780`。建议前端配合 Nginx / Caddy 开启 HTTPS 反向代理以保障传输安全。

---


## ⚖️ 免责声明与使用边界

1. **内容权利归属**：乐屿（Melora）仅为个人媒体播放与流媒体技术探索工具，本身不提供、不存储、不分发任何音乐音频、有声作品、封面图片或歌词等第三方版权材料。
2. **音源使用合规**：所有展示与播放内容均取决于用户自行配置的网络源或导入的自管脚本。请在法律法规及各平台许可范围内使用，使用者须对自身内容获取行为负责。
3. **许可证说明**：本仓库当前**未包含开源商用许可证**。公开源代码不代表授予任何商业复用、二次分发或许可转让权利。
4. 详细法律与使用边界条款请参阅 [DISCLAIMER.md](DISCLAIMER.md)。

---

<div align="center">
  <sub>Made with ❤️ for music lovers.</sub>
</div>
