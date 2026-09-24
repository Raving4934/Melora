# 更新日志

## [Unreleased]

## [0.1.4] - 2026-09-24

### 安卓客户端

【歌单与播放队列】
- 支持从网易云、QQ 音乐、酷我、酷狗、咪咕的公开歌单分享链接导入歌曲；完善移动端链接识别，入口放在“新建歌单”标题右侧。
- 自建歌单更多菜单新增“添加所有歌曲到播放队列”，无需替换当前队列或打断正在播放的歌曲。
- 列表循环、单曲循环和随机播放模式可在重启后恢复，并纳入备份；听书仍按章节顺序播放。
- 修复清空队列后界面残留、队列意外恢复，以及快速切歌时清除已播放条目可能错位的问题。

【歌词与播放器】
- 完善桌面歌词：与播放器共用逐词渲染，窗口随内容调整，拖动位置可记忆并随备份恢复；全屏歌词工具栏可直接开关桌面歌词。
- 统一歌词匹配、来源选择和本地文件写入，保留真实逐词时间信息，改善在线与本地歌曲的歌词一致性。
- 全屏歌词的字号、对齐、加粗和模糊设置支持持久化与备份；精简歌词来源抽屉，修复大字号摘要裁切。
- 长按封面可打开外观卡片，集中选择封面样式与播放主题，并保留明确的沉浸播放入口。

【缓存与音源】
- 同一录音、同档实测音质的完整音频缓存可跨来源复用，供播放、下载和后台补齐共用，不拼接不同来源的音频片段。
- 完整缓存命中时显示“播放源：缓存”；缓存失效后重新解析时显示解析状态，不再沿用上次的缓存标签。
- 统一共享解析请求和物理资源失败冷却，避免取消一个请求影响其他调用，以及重新解析再次选中同一个失败资源。
- 完善页面、封面与歌词缓存的容量控制、按需恢复和清理反馈，减少批量操作中的重复文件系统访问。
- 音源文件选择器限制为 JavaScript 文件，轻量校验拦截误导入；脚本执行延后，避免导入时执行整份脚本。修复检查更新与本地替换、备份恢复之间的覆盖冲突。

【下载与本地音乐】
- 本地歌曲下载更高音质前先检查实际规格；没有更优版本时不新增下载记录，升级失败保留原文件。
- 下载遇到音频传输失败时可在限定次数内自动换源重试，不把不同资源拼接到同一文件；完善暂停、取消、任务冲突和下载完成记录处理。
- 完善下载文件被外部删除后的本地标记、记录与播放回退，支持本地歌曲批量删除授权，避免继续将缺失文件当作可播放的本地歌曲。
- 本地歌曲排序及字母索引在后台生成，补全实际码率徽标，减少大列表占用主线程的工作。

【稳定性与交互】
- 修复备份恢复中断后的重试、启动恢复及前台服务处理，并调整原生恢复弹窗的配色和文字层级。
- 修复脚本引擎初始化失败时的异常传递和资源清理，避免相关崩溃或长时间无响应。
- 完善发现、听书等页面的导航与加载状态恢复，避免播放器详情页残余滑动速度误触收起。
- 优化歌单创建、导入和重试状态，减少短暂加载文字闪烁；修复新歌推荐已到末尾仍显示“加载更多”的问题。

### 提交追溯（安卓版）

> 仅发布 Android 0.1.4；Web / NAS、FPK 与 Docker 维持 0.1.1。完整提交区间：[android-v0.1.3...android-v0.1.4][Android 0.1.4]。

- 公开歌单链接导入与入口布局：[`6d50f81`](https://github.com/Raving4934/Melora/commit/6d50f8189a4f0a95e2b2e97aa7984c8e0d748581)、[`997e0e4`](https://github.com/Raving4934/Melora/commit/997e0e4b9afa4e85451c193f5f6580df45a902c4)、[`65aa018`](https://github.com/Raving4934/Melora/commit/65aa018a9ab3c906e0e6bda093b37c2d7316ca1e)、[`ef8ade1`](https://github.com/Raving4934/Melora/commit/ef8ade1908c68f931266eb6488e6293a3386d4dd)。
- 队列追加、清空同步与播放模式记忆：[`fb244df`](https://github.com/Raving4934/Melora/commit/fb244df318c0c55f186769a575942f8a0162473e)、[`b64a467`](https://github.com/Raving4934/Melora/commit/b64a46729729094a2968fc4c7d2586b0f434e842)、[`b26826f`](https://github.com/Raving4934/Melora/commit/b26826f900262e94d29f77a1ae739f49109053a0)、[`2d0b5af`](https://github.com/Raving4934/Melora/commit/2d0b5af8ead9f4961916f2df33004281350c8895)。
- 封面外观卡片与详情滚动边界：[`4c1fc76`](https://github.com/Raving4934/Melora/commit/4c1fc763670b0d4035d08bbb6a479cb14c4fb0e2)、[`d60e740`](https://github.com/Raving4934/Melora/commit/d60e740b6a977c02e00f149af1c0126f6ebe24f5)。
- 逐词歌词匹配、写入与外观持久化：[`e029097`](https://github.com/Raving4934/Melora/commit/e029097b607b4e5f2db80c193f45fff52d2f434f)、[`6cf87d4`](https://github.com/Raving4934/Melora/commit/6cf87d44a224380478a8136683b21696b45dbaa0)、[`7d13866`](https://github.com/Raving4934/Melora/commit/7d1386648cd3a97f378677418a29cb5a10dd4d07)、[`58dcbaf`](https://github.com/Raving4934/Melora/commit/58dcbafbe971b0a7646f90c92c585e16bcd1b3e1)。
- 桌面歌词渲染、位置记忆与快捷开关：[`e06b34b`](https://github.com/Raving4934/Melora/commit/e06b34bc5f06eb78c2bebeb1e03e3dcf56ab537e)、[`86d7021`](https://github.com/Raving4934/Melora/commit/86d70210c91b866999f7c43baf71328680e10cec)。
- 页面、封面、歌词缓存及批量开销：[`cf60d55`](https://github.com/Raving4934/Melora/commit/cf60d55d003c666b5736b849024cfa31c276e467)、[`220b2c3`](https://github.com/Raving4934/Melora/commit/220b2c32b118d776c59e14f61666e1dc4c23c2b1)、[`b7cc845`](https://github.com/Raving4934/Melora/commit/b7cc84507e5c10375293feb698f4ac8ff1f292b0)、[`60154cc`](https://github.com/Raving4934/Melora/commit/60154cc57fe10f46e7bc2e9b28023b41049812d9)。
- 完整音频缓存跨来源复用与来源状态：[`9b30413`](https://github.com/Raving4934/Melora/commit/9b30413d8fb555cbc6e6da023cc315b3700860aa)、[`2f4f588`](https://github.com/Raving4934/Melora/commit/2f4f58869c934af6f2a30c246ad5b718c2db5040)、[`dad4733`](https://github.com/Raving4934/Melora/commit/dad4733a4506f9bbe22e217a59c6a31309268095)。
- 下载音质预检、任务与完成记录：[`c53ae1d`](https://github.com/Raving4934/Melora/commit/c53ae1dcdd79848e5523c1553a099c111640f007)、[`1cc01a7`](https://github.com/Raving4934/Melora/commit/1cc01a7ebf31c68bf1fc195ddd77b66bee4c9cd4)、[`c448612`](https://github.com/Raving4934/Melora/commit/c44861258ede40c84ac4b862760c8dba1896a0bb)、[`667e046`](https://github.com/Raving4934/Melora/commit/667e0460996179f0f5fa5a76eeff1026c964611f)。
- 播放／下载失败恢复与共享解析：[`3cb93c9`](https://github.com/Raving4934/Melora/commit/3cb93c9e1678e318290155487721d2e49e6e1081)、[`7a1cff4`](https://github.com/Raving4934/Melora/commit/7a1cff430da2824ac649374a5203aec00a8e7e92)、[`58a8d43`](https://github.com/Raving4934/Melora/commit/58a8d4376085abe119950823d2e43a84758f966a)。
- 本地删除、文件失效、徽标与后台排序：[`095e5a4`](https://github.com/Raving4934/Melora/commit/095e5a413e6a3f7a94dee3c33dc9dd6261353852)、[`9d1d7c1`](https://github.com/Raving4934/Melora/commit/9d1d7c134e15b64eaeb877673720286668d2c05e)、[`b311311`](https://github.com/Raving4934/Melora/commit/b311311108c97048dfbd5894694d942a007a9e9e)、[`2a4b325`](https://github.com/Raving4934/Melora/commit/2a4b3256aa284289bc699c2df06b5a24291bdfe6)。
- 音源文件选择、轻量校验与更新冲突：[`eb537fe`](https://github.com/Raving4934/Melora/commit/eb537fe5a3b4258ad63453aa67e80d780b2c6a00)、[`f6f3d10`](https://github.com/Raving4934/Melora/commit/f6f3d1023a1bcbcf0934a519876c0215dec152ee)、[`7c3f7eb`](https://github.com/Raving4934/Melora/commit/7c3f7eba65569f0b24dd7115a175adc31b01c54b)、[`79e532c`](https://github.com/Raving4934/Melora/commit/79e532ced5e562da2721698bda425bf659b0563e)。
- 备份、启动恢复与脚本引擎异常：[`3fd1bd8`](https://github.com/Raving4934/Melora/commit/3fd1bd86c9e49bd0b176cfcb931fd378de34629a)、[`031c19a`](https://github.com/Raving4934/Melora/commit/031c19a51210166c011dc8ddc049a49cfa6a8601)、[`660d9ba`](https://github.com/Raving4934/Melora/commit/660d9bae87aa3d7e1aa2d3df99ea724ba2ffc011)。
- 导航恢复、歌单抽屉与推荐列表边界：[`a8a8277`](https://github.com/Raving4934/Melora/commit/a8a82777e3d79f9cadd8dc2690b3185d9a50a55f)、[`3da0fde`](https://github.com/Raving4934/Melora/commit/3da0fdea8c97a0d4216d2d9b8c41ea26510efa80)、[`b46ce57`](https://github.com/Raving4934/Melora/commit/b46ce5745e6fc9f54a21ca31289e6b51feaa96d2)。

### 安卓安装包

- [下载 Android 0.1.4 APK（arm64-v8a）](https://github.com/Raving4934/Melora/releases/download/android-v0.1.4/melora-android-v0.1.4-arm64-v8a.apk)
- 适用于 Android 8.0 及以上的 ARM64 设备；正式签名保持不变，可直接覆盖安装。

## [0.1.3] - 2026-09-23

### 安卓客户端

【听书体验】
- 最近收听卡片支持从上次进度继续播放，不再重新从头开始。
- 直接点播任意章节后，按本书顺序继续播放，并自动补充后续章节，无需先点击“播放全部”再查找章节。
- 听书的“参与创作的艺术家”改为展示作者／主播的整部作品，点击作品进入章节列表，不再混杂不同书籍的单集节目。
- 听书目录返回重入时保留已加载内容与分页位置，改善长篇章节和作者作品的连续浏览体验。

【播放与本地音乐】
- 删除本地歌曲后，同步清理当前队列、保存的队列和待播放请求，避免重启后已删除的歌曲再次出现。
- 删除非当前歌曲不打断播放；删除当前歌曲后接续剩余歌曲，原本暂停则保持暂停，单曲循环也可正常接续。
- 批量删除只清理成功删除的文件；保留同名的其他音质文件及在线歌曲，文件权限或存储暂时不可用时不贸然清空队列。

【交互与配置】
- 全屏播放页打开的专辑／艺术家详情不再触发下拉收起，避免与列表滚动冲突；顶部返回和系统返回照常使用。
- 目录数据通道改为自动选择与失败回退，移除手动切换选项，无需再选择 App／网页通道。

### 提交追溯（安卓版）

> 仅发布 Android 0.1.3；Web / NAS、FPK 与 Docker 维持 0.1.1。完整提交区间：[android-v0.1.2...android-v0.1.3][Android 0.1.3]。

- 听书续播、章节顺序与分页连播：[`a40dfa1`](https://github.com/Raving4934/Melora/commit/a40dfa1f211649345af1eec2d72e12b5454a4ca0)。
- 目录数据通道自动选择：[`3853953`](https://github.com/Raving4934/Melora/commit/3853953c0ca176e2a4042b280489fec580ffcc04)。
- 作者／主播作品目录与听书目录分页恢复：[`332d2f1`](https://github.com/Raving4934/Melora/commit/332d2f10d20fd967a972c4f13ef676abddb7dfbc)。
- 本地文件删除与播放队列同步：[`e296346`](https://github.com/Raving4934/Melora/commit/e296346018e80eb9adbc2ec41304b37a6008f805)。
- 播放器详情页滚动手势修复：[`6682568`](https://github.com/Raving4934/Melora/commit/6682568c54afe121d25ea4e733280a1ab97dfe9c)。

### 安卓安装包

- [下载 Android 0.1.3 APK（arm64-v8a）](https://github.com/Raving4934/Melora/releases/download/android-v0.1.3/melora-android-v0.1.3-arm64-v8a.apk)
- 适用于 Android 8.0 及以上的 ARM64 设备；正式签名保持不变，可直接覆盖安装。

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
- 统一听书与长音频的断点续播：列表点播、队列点选、上下首、自动连播和重启恢复使用同一套记忆；暂停和切歌保存实际位置，补齐时长信息缺失时的恢复，并优先保留用户主动拖动的位置。
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

- 全入口进度记忆、暂停保存与实际时长恢复：[`e29f220`](https://github.com/Raving4934/Melora/commit/e29f220aa4de6019f571e30e27281d30ee84db2d)。

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

[Unreleased]: https://github.com/Raving4934/Melora/compare/android-v0.1.4...HEAD
[Android Unreleased]: https://github.com/Raving4934/Melora/compare/android-v0.1.4...HEAD
[Web Unreleased]: https://github.com/Raving4934/Melora/compare/v0.1.1...HEAD
[0.1.4]: https://github.com/Raving4934/Melora/compare/android-v0.1.3...android-v0.1.4
[Android 0.1.4]: https://github.com/Raving4934/Melora/compare/android-v0.1.3...android-v0.1.4
[0.1.3]: https://github.com/Raving4934/Melora/compare/android-v0.1.2...android-v0.1.3
[Android 0.1.3]: https://github.com/Raving4934/Melora/compare/android-v0.1.2...android-v0.1.3
[0.1.2]: https://github.com/Raving4934/Melora/compare/android-v0.1.1...android-v0.1.2
[Android 0.1.2]: https://github.com/Raving4934/Melora/compare/android-v0.1.1...android-v0.1.2
[0.1.1]: https://github.com/Raving4934/Melora/compare/v0.1.0...v0.1.1
[Android 0.1.1]: https://github.com/Raving4934/Melora/compare/android-v0.1.0...android-v0.1.1
[0.1.0]: https://github.com/Raving4934/Melora/releases/tag/v0.1.0
[Android 0.1.0]: https://github.com/Raving4934/Melora/releases/tag/android-v0.1.0
