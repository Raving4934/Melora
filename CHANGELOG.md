# 更新日志

## [Unreleased]

## [0.1.2] - 2026-09-21

### 安卓客户端

【动画与交互】
- 重做页面进入与返回动画：相邻详情页在同一平面连续平移，前后页面自然衔接，不再各自淡入淡出。
- 完善侧栏切页，以及发现页歌单广场、官方榜单、听书目录和听书榜单、搜索的进出过渡；返回时保留原页面内容与位置。
- 模糊标题栏与系统状态栏联动过渡，改善切页时的分割线、首帧跳位和双层标题衔接。
- 点击封面下方歌词，改为与手势翻页一致的滑动动画；途中可反向拖动接管，普通与沉浸播放共用同一套交互。
- 调整列表回顶与滚动体验，去除抽屉列表边缘的回弹干扰。

【新增功能】
- 沉浸播放：长按封面进入或退出，隐藏常驻操作栏和系统状态栏，展示居中大歌词、透视封面与沿封面绘制的播放进度；轻触封面可唤出临时播放控制，横屏增加歌名背景水印。
- 原生逐词歌词：统一全屏与迷你歌词渲染，支持增强 LRC、Apple/AMLL 风格 TTML 的逐词填充、翻译、音译、对唱及背景人声；普通 LRC 继续逐行高亮，手动浏览后自动恢复跟随。
- 动态封面背景：支持 Android 13 及以上设备的封面背景流动效果，随播放状态启停，并尊重省电模式和系统动画设置。
- 本地音乐拼音排序与字母索引：按歌曲名称或歌手分组，支持数字、符号及字母快速定位，也可拖动索引连续跳转。
- 双击标题快速回顶，覆盖主要列表与详情页面；单击标题不会触发回顶或误点下方歌曲。

【体验修复】
- 本地歌曲排序方式和方向可持久保存，并随备份恢复；修复空态音符遮挡、搜索光标错位及搜索退场时内容提前清空。
- 百万热播页面返回重入时保留已加载数据与分页进度，歌单加载失败后重试不再反复闪出骨架屏，也不会重复发起请求。
- 调整发现页顶部卡片与热搜文字布局，改善内容截断和加载时的位置变化。

### 提交追溯（安卓版）

> 仅发布 Android 0.1.2；Web / NAS、FPK 与 Docker 维持 0.1.1。完整提交区间：[android-v0.1.1...android-v0.1.2][Android 0.1.2]。

- 页面导航、标题回顶、触摸拦截与布局稳定：[`baca3ea`](https://github.com/Raving4934/Melora/commit/baca3ea0a2a1424c9621cca03f3fae841040dea1)、[`825daf9`](https://github.com/Raving4934/Melora/commit/825daf90e20204d801ac4fbe096f89279369cdfc)、[`2d7eb15`](https://github.com/Raving4934/Melora/commit/2d7eb15daac3f48b4ffcf0021deea5d2ba98fac5)、[`1ad31b4`](https://github.com/Raving4934/Melora/commit/1ad31b46fd45ed993552f91966329617dc4b8fd8)、[`22c53b7`](https://github.com/Raving4934/Melora/commit/22c53b771b4689b82b0d056d620f1662504a5956)、[`bc478a0`](https://github.com/Raving4934/Melora/commit/bc478a0975eddf23e0c2b68fa3afb71f98d92f87)、[`0e88a55`](https://github.com/Raving4934/Melora/commit/0e88a55d346241231bda9038af50162adcf2bb12)。
- 原生时序歌词与高亮、对齐：[`427f961`](https://github.com/Raving4934/Melora/commit/427f9618fbb2573e20cd6046d698fb5e59ec53c7)、[`1de74c6`](https://github.com/Raving4934/Melora/commit/1de74c60758a47523210e31e5c7fd1ef5ac53ad1)、[`1df5050`](https://github.com/Raving4934/Melora/commit/1df5050fb1de1c922ef801cfef7d5dd9cb3faf48)。
- 封面背景动效：[`9a3eab4`](https://github.com/Raving4934/Melora/commit/9a3eab40dbb8b59a08885102d15eb864c910d143)。
- 本地排序保存、拼音索引及空态和输入修复：[`27ee026`](https://github.com/Raving4934/Melora/commit/27ee026e76c0bb4b0d2bcb504932cbdb98816a63)、[`61b9398`](https://github.com/Raving4934/Melora/commit/61b9398301f4a6b960c52725719e4154c168caa6)、[`980b7d7`](https://github.com/Raving4934/Melora/commit/980b7d7bac8d82d1d1323916448d4cb3ca9b0216)。
- 百万热播重入与歌单重试：[`f0f8634`](https://github.com/Raving4934/Melora/commit/f0f863434fade5ee6ed809271a986f88410f7f2d)、[`410b9de`](https://github.com/Raving4934/Melora/commit/410b9de6f346b3f2998950a10d11963a38685725)。
- 沉浸播放与点击歌词分页动画：[`20c2f64`](https://github.com/Raving4934/Melora/commit/20c2f64e3a4979367a02bb95795b0248a9e97893)、[`c17cc97`](https://github.com/Raving4934/Melora/commit/c17cc97abdd0cd740baf2c49bb74b93a1d74b724)。
- 发布签名校验与公开仓检查修复：[`b8caebd`](https://github.com/Raving4934/Melora/commit/b8caebdcc6f71bbb281dbc2da9a3111db311b198)、[`b977dae`](https://github.com/Raving4934/Melora/commit/b977dae10323a1b0128bc7b9fac184e52b2ed791)。
- 双语社区致谢：[`71acbc4`](https://github.com/Raving4934/Melora/commit/71acbc45f61578f8bb815c9e4f311b411d608850)。

### 安卓安装包

- [下载 Android 0.1.2 APK（arm64-v8a）](https://github.com/Raving4934/Melora/releases/download/android-v0.1.2/melora-android-v0.1.2-arm64-v8a.apk)
- 适用于 Android 8.0 及以上的 ARM64 设备。

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

[Unreleased]: https://github.com/Raving4934/Melora/compare/android-v0.1.2...HEAD
[Android Unreleased]: https://github.com/Raving4934/Melora/compare/android-v0.1.2...HEAD
[Web Unreleased]: https://github.com/Raving4934/Melora/compare/v0.1.1...HEAD
[0.1.2]: https://github.com/Raving4934/Melora/compare/android-v0.1.1...android-v0.1.2
[Android 0.1.2]: https://github.com/Raving4934/Melora/compare/android-v0.1.1...android-v0.1.2
[0.1.1]: https://github.com/Raving4934/Melora/compare/v0.1.0...v0.1.1
[Android 0.1.1]: https://github.com/Raving4934/Melora/compare/android-v0.1.0...android-v0.1.1
[0.1.0]: https://github.com/Raving4934/Melora/releases/tag/v0.1.0
[Android 0.1.0]: https://github.com/Raving4934/Melora/releases/tag/android-v0.1.0
