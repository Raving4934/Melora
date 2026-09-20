# 更新日志

## [0.1.1] - 2026-09-21

### 安卓客户端

- 新增本地文件与 HTTP/HTTPS 直链统一导入，可检查并更新通过链接安装的音源。
- 音源合集会在写盘前完成大小、数量与重名校验，避免部分导入或静默覆盖。
- 备份与恢复现会完整保留音源订阅链接；旧备份仍可无损读取，并会清理过期链接状态。
- 统一脚本元数据解析与脚本池刷新路径，脚本更新后不再沿用旧版本运行指标。
- 修复可选模块初始化失败时首屏永久等待的问题，并稳定音源列表项的 Compose 状态身份。

[![Android APK](https://img.shields.io/badge/Android-下载%20v0.1.1%20APK%20(arm64--v8a)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Raving4934/Melora/releases/download/android-v0.1.1/melora-android-v0.1.1-arm64-v8a.apk)

> 适用于 Android 8.0 及以上的 ARM64 设备；正式发布后下载入口生效。

## [0.1.0] · 初版

> 当前仅同步源码，尚未发布新构建。以下为预设下载入口，待正式发布后生效。

### 📱 Android 客户端

- 原生 Android 音乐播放器，独立运行，无需 NAS 或服务器。
- 支持音乐搜索、排行榜、发现、歌单、听书与本地音乐。
- 支持播放队列、歌词、音效、下载和备份。

[![Android APK](https://img.shields.io/badge/Android-下载%20APK%20(arm64--v8a)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Raving4934/Melora/releases/download/android-v0.1.0/melora-android-v0.1.0-arm64-v8a.apk)

> 适用于 Android 8.0 及以上的 ARM64 设备。

### 🖥️ Web / NAS、FPK 与 Docker

- 提供 Web 音乐播放器，支持 Docker 自托管及飞牛 fnOS 部署。
- 支持公开音乐目录浏览、兼容 LX、音乐库与播放管理。

| 版本 | 快速下载 |
| --- | --- |
| 飞牛 FPK · amd64 | [下载安装包](https://github.com/Raving4934/Melora/releases/download/v0.1.0/melora-0.1.0-linux-amd64.fpk) |
| 飞牛 FPK · arm64 | [下载安装包](https://github.com/Raving4934/Melora/releases/download/v0.1.0/melora-0.1.0-linux-arm64.fpk) |
| Docker Compose | [部署配置](https://github.com/Raving4934/Melora/releases/download/v0.1.0/docker-compose.yml) |

**Docker 镜像：** `ghcr.io/raving4934/melora:0.1.0`（支持 `linux/amd64`、`linux/arm64`，待发布后可拉取）。
