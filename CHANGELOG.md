# 更新日志

## [Unreleased]

## [0.1.1] - 2026-09-20

### 安卓客户端

> 完整提交区间：[android-v0.1.0...android-v0.1.1][Android 0.1.1]

- 新增本地文件与 HTTP/HTTPS 直链统一导入，可检查并更新通过链接安装的音源；HTTP 明文连接会显示风险提示（[`063b8a1`](https://github.com/Raving4934/Melora/commit/063b8a1231e3aaa001ebf1fa8a750a42033a8201)）。
- 音源合集会在写盘前完成大小、数量与重名校验，避免部分导入或静默覆盖（[`063b8a1`](https://github.com/Raving4934/Melora/commit/063b8a1231e3aaa001ebf1fa8a750a42033a8201)）。
- 备份与恢复现会完整保留音源订阅链接；旧备份仍可无损读取，并会清理过期链接状态（[`063b8a1`](https://github.com/Raving4934/Melora/commit/063b8a1231e3aaa001ebf1fa8a750a42033a8201)）。
- 统一脚本元数据解析与脚本池刷新路径，脚本更新后不再沿用旧版本运行指标（[`063b8a1`](https://github.com/Raving4934/Melora/commit/063b8a1231e3aaa001ebf1fa8a750a42033a8201)）。
- 修复可选模块初始化失败时首屏永久等待（[`84a62c8`](https://github.com/Raving4934/Melora/commit/84a62c869d309d1fe071274bea5139d8005bbcb8)）。
- 修复音源检查状态更新导致操作抽屉闪烁的问题（[`0300465`](https://github.com/Raving4934/Melora/commit/0300465cb6be6f23f36c565b39ee95bc288f0717)）。
- 补充非官方交流群与第三方引流风险说明（[`6195ba4`](https://github.com/Raving4934/Melora/commit/6195ba491b06bc179fa153ec3eb702efd179d0bb)）。

[![Android APK](https://img.shields.io/badge/Android-下载%20v0.1.1%20APK%20(arm64--v8a)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/Raving4934/Melora/releases/download/android-v0.1.1/melora-android-v0.1.1-arm64-v8a.apk)

> 适用于 Android 8.0 及以上的 ARM64 设备。

### Web / NAS、FPK 与 Docker

> 完整提交区间：[v0.1.0...v0.1.1][0.1.1]

- 新增通过公网 HTTP/HTTPS 链接导入兼容音源脚本（[`49295ac`](https://github.com/Raving4934/Melora/commit/49295ac8198bc8e27fe67cd5137e47622b4556ce)、[`eb0c329`](https://github.com/Raving4934/Melora/commit/eb0c32965435547f031f7b69415bc3e699ae62e5)）。
- 保留公网地址、DNS 结果、重定向与响应大小校验，阻止本地地址、私网地址及 HTTPS 降级到 HTTP（[`49295ac`](https://github.com/Raving4934/Melora/commit/49295ac8198bc8e27fe67cd5137e47622b4556ce)）。
- Web 导入界面会提示 HTTP 明文传输风险，并保持导入错误和加载状态稳定显示（[`eb0c329`](https://github.com/Raving4934/Melora/commit/eb0c32965435547f031f7b69415bc3e699ae62e5)）。

| 版本 | 快速下载 |
| --- | --- |
| 飞牛 FPK · amd64 | [下载安装包](https://github.com/Raving4934/Melora/releases/download/v0.1.1/melora-0.1.1-linux-amd64.fpk) |
| 飞牛 FPK · arm64 | [下载安装包](https://github.com/Raving4934/Melora/releases/download/v0.1.1/melora-0.1.1-linux-arm64.fpk) |
| Docker Compose | [部署配置](https://github.com/Raving4934/Melora/releases/download/v0.1.1/docker-compose.yml) |

**Docker 镜像：** `ghcr.io/raving4934/melora:0.1.1`（支持 `linux/amd64`、`linux/arm64`）。

## [0.1.0] · 初版

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

**Docker 镜像：** `ghcr.io/raving4934/melora:0.1.0`（支持 `linux/amd64`、`linux/arm64`）。

[Unreleased]: https://github.com/Raving4934/Melora/compare/v0.1.1...HEAD
[Android Unreleased]: https://github.com/Raving4934/Melora/compare/android-v0.1.1...HEAD
[0.1.1]: https://github.com/Raving4934/Melora/compare/v0.1.0...v0.1.1
[Android 0.1.1]: https://github.com/Raving4934/Melora/compare/android-v0.1.0...android-v0.1.1
[0.1.0]: https://github.com/Raving4934/Melora/releases/tag/v0.1.0
[Android 0.1.0]: https://github.com/Raving4934/Melora/releases/tag/android-v0.1.0
