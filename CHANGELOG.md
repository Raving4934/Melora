# 更新日志

## [Unreleased]

## [0.1.8] - 2026-10-02

### 安卓客户端

> 本次仅更新 Android 客户端至 0.1.8（versionCode 17）；Web / NAS、FPK 与 Docker 维持 0.1.1。

【逐字歌词与阅读体验】
- 修复播放位置周期校正造成的逐字歌词抢跑、填充回退；保留拖动进度、暂停、缓冲、倍速及切歌时的真实时间跳转。
- 全屏与封面 mini 歌词在间奏、尾奏保留已唱句的阅读焦点，下一句到实际起唱时间才交接，不添加等待提示或伪造逐字时间。
- 全屏歌词文字跟随播放页封面取色；普通全屏的虚化开关启用实际分层模糊，焦点句保持清晰，邻句轻虚、远句更虚。
- 调整普通全屏焦点与非焦点歌词的视觉大小差异，通过图层缩放保持原有换行与行距；mini 字号和布局不变。

【播放器与歌单】
- 修复打开全屏播放页后，经过最近任务返回应用时，底层搜索输入框残留焦点导致输入法意外弹出的问题。
- 修复沉浸模式进入及中途反向切换时封面下沉的问题，保持封面内容与位置连续。
- 歌单更新仅面向已记录导入来源的歌单；手动创建及没有原歌单来源的歌单不再显示绑定来源、从原歌单更新入口。

【构建与验证】
- 修复 Android CI 设备路径初始化和 SDK 命令行工具包列表识别问题。
- 补充播放时钟、服务连续播放、输入焦点、间奏阅读焦点、浅深色歌词取色及模糊层次回归测试。

### 关键提交

- Android CI 修复：[`1e887f8`](https://github.com/Raving4934/Melora/commit/1e887f8b034d12a0efd61ce9132960c9ca95c388)、[`ebea79e`](https://github.com/Raving4934/Melora/commit/ebea79ec841b3f2cf4c21daa2f578cc6822ed192)。
- 沉浸封面位置：[`bae4dca`](https://github.com/Raving4934/Melora/commit/bae4dca4d2a46bf8c54d58f8dbd3bcc72df437ee)；歌单更新来源：[`62bea41`](https://github.com/Raving4934/Melora/commit/62bea417627ccb07e7920bab2b20928db30b66e5)。
- 输入法焦点：[`1d8c608`](https://github.com/Raving4934/Melora/commit/1d8c60862cc0f69277f73064e35e4f2433cf4bad)；逐字歌词时钟：[`6f9e331`](https://github.com/Raving4934/Melora/commit/6f9e331c812a6e2379b5cc3716cfb82d3fab69ef)。
- 歌词阅读焦点与取色景深：[`4dc892e`](https://github.com/Raving4934/Melora/commit/4dc892e517a34c3f8a858f2bd88c7e8b8eea3a99)。

### 安卓安装包

- Android 0.1.8 APK（arm64-v8a）：由 Android 发布工作流构建、校验并上传。

## [0.1.7] - 2026-10-01

### 安卓客户端

> 本次仅更新 Android 客户端至 0.1.7（versionCode 16）；Web / NAS、FPK 与 Docker 维持 0.1.1。

【应用内 APK 更新】
- 可在应用内检查并下载更新 APK，查看下载进度或取消；下载完成后校验包信息、版本与签名，再交由 Android 系统安装。权限恢复和重试可在更新面板中完成。

【下载、取消与本地写入】
- 修复下载任务等待容量变化时的响应问题；升级中断后若目标文件已发布，可恢复原完成记录，避免重复处理。
- 下载和预取取消时同步结束底层网络与音源脚本任务；迟到的脚本结果不会串入后续请求。
- 本地歌曲标签写入前核对文件快照，避免文件被替换或截断后仍写入旧元数据；保留手动重试及未知元数据处理。
- 歌单批量添加改为一次去重事务；无变化时跳过写入，失败时保留原有回滚行为。

【列表与播放界面】
- 统一歌曲列表选择状态的渐隐动效并稳定行内操作位置，修复选择按钮淡出时影响“更多”操作的问题。
- 底部弹窗关闭时完整播放退场动画，遮罩随拖动位置同步变化；歌单导入后转为更新可复用同一弹窗并保留预览确认流程。
- 统一播放面板与队列吸附动效，保持转场中的不透明背景；播放器移动时复用静态底层画面。
- 封面样式切换连续过渡，覆盖封面形状、唱片标签与沉浸式描边，并同步调整封面尺寸、位置和迷你歌词对齐。

【队列、返回与恢复】
- 播放队列页返回原入口；普通播放页按层级收起，沉浸播放继续保留原有返回行为。播放器转场中首次返回可打断进入动画，横向翻页、纵向手势与队列交接分开处理。
- 从通知栏退出播放时保留队列、当前歌曲和播放位置，不关闭应用 Activity；重新打开应用后恢复迷你播放器。
- 队列展示按播放器引擎的真实播放顺序定位当前歌曲，同时保留原队列数据、手动浏览及下拉交接行为。
- 启动恢复过程直接观察播放就绪状态，沿用既有超时与队列回退策略。

【质量验证】
- 补充下载恢复、取消、本地文件变更、弹窗拖动、播放器导航、队列顺序与通知栏退出播放等回归覆盖；隔离异步缓存测试生命周期，并将 API 35 全量仪器测试设为 CI 默认门禁。

### 提交追溯（安卓版）

> 完整提交区间：[android-v0.1.6...android-v0.1.7][Android 0.1.7]。

- 应用内 APK 下载、校验与安装交接：[`74b10bd`](https://github.com/Raving4934/Melora/commit/74b10bdfbd6fb58b8f09a48fd913d7522d492a51)。
- 下载任务响应与中断恢复：[`91c96d0`](https://github.com/Raving4934/Melora/commit/91c96d0b1e50704d7286ab0e12e34a2166acdd24)；音频及目录 IO 取消传递：[`f8659bb`](https://github.com/Raving4934/Melora/commit/f8659bbec483bc1a8648a027e96393714bc100cd)；本地标签写入快照保护：[`d1e4398`](https://github.com/Raving4934/Melora/commit/d1e439819db4f3c678ee09ede55ad88e1af133fd)。
- 播放就绪恢复观察：[`a6b317a`](https://github.com/Raving4934/Melora/commit/a6b317ac69e2bc345f9da161b592cdfa6cf68c66)；歌单批量添加事务：[`a6b59c2`](https://github.com/Raving4934/Melora/commit/a6b59c2a2f01cabb621c31eefa1a1dd9e50782e8)。
- 歌曲行选择动效与操作区域：[`fabb054`](https://github.com/Raving4934/Melora/commit/fabb0540c996e834fcd5f71ec03bfcb4bdf8a1b1)。
- 播放面板转场：[`6b9af37`](https://github.com/Raving4934/Melora/commit/6b9af37d4af4197eb70aca229bb2874cb1f77f30)；弹窗退场与歌单更新窗口复用：[`2d96490`](https://github.com/Raving4934/Melora/commit/2d96490cc5ec6d99307be89e39a2b1ddeea09ff5)；拖动遮罩同步：[`60e919c`](https://github.com/Raving4934/Melora/commit/60e919c5ae07e88e446b03b47a0324867e8305c4)；播放转场底层复用：[`1ddf511`](https://github.com/Raving4934/Melora/commit/1ddf5115d7b6582b74a5e7bbeae912945bbdd43a)。
- 队列返回来源页面：[`bbec5a4`](https://github.com/Raving4934/Melora/commit/bbec5a4615d092dcea173618ef522c1f82a5331a)；播放器手势、返回与队列交接：[`3328932`](https://github.com/Raving4934/Melora/commit/3328932bfc8318dd84e6bac4f89a47de3756b218)；通知栏退出播放保留队列：[`6989570`](https://github.com/Raving4934/Melora/commit/69895706d844ad3aa290926b73efd362d78116a3)；按引擎顺序展示队列：[`219a82d`](https://github.com/Raving4934/Melora/commit/219a82dd96c82d9942967e58d4aafccef36a7fc5)。
- 封面样式连续过渡：[`1eb768d`](https://github.com/Raving4934/Melora/commit/1eb768d8614ed71790ce31caefa9c231a4438a06)。
- 测试缓存生命周期与自适应视口隔离：[`0774fa2`](https://github.com/Raving4934/Melora/commit/0774fa2cee054d78dfd6e537cb012268d5c193aa)；Android 仿真器回归 CI 门禁：[`9244507`](https://github.com/Raving4934/Melora/commit/9244507f0a3312a0514ae9d8953f5450bf1b1fb4)。

### 安卓安装包

- Android 0.1.7 APK（arm64-v8a）：正式发布后下载。

## [0.1.6] - 2026-09-29

### 安卓客户端

> 2026-09-29 同版本重发：版本名仍为 0.1.6，内部版本号由 14 递增为 15。已安装旧 0.1.6 的用户请重新下载 APK 覆盖安装；同版本不会再次提示更新，无需清空数据。

【歌单手动更新】
- 自建歌单支持“从原歌单更新”：记录导入来源，先读取并预览新增、移除的歌曲，再由用户确认保存；仅手动更新，不定时自动同步。
- 更新保留本地歌单名称和额外添加的歌曲，按原歌单顺序整理；在本地删除但原歌单仍有的歌曲会恢复。旧歌单可绑定原链接，首次绑定保留全部现有歌曲并合并远端内容。
- 增强同一来源歌单的重复导入识别：可选择更新已有歌单，或明确另存为副本；存在多个本地副本时需选择更新目标。
- 歌单来源和同步基线随备份保存与恢复；读取不完整、跨页重叠、来源不符或本地歌单发生冲突时拒绝更新，避免误改已有数据。

【歌单弹窗】
- 整理导入与更新弹窗的信息层级，增加歌单封面摘要与清晰的读取、预览、异常状态；无变化时可直接“完成”，不执行多余写入。
- 导入链接支持折叠，编辑和重新读取集中在链接栏；重复导入时，“另存为副本”与“更新已有歌单”组成底部操作组，关闭入口置于右上角。
- 正文独立滚动、底部操作保持可达，改善长链接、多副本、大字号及键盘弹出时的布局；候选歌单不再展示内部 ID。

【返回与通知】
- 应用主界面增加连续两次返回退出确认；内部页面、侧栏和播放器仍按层级返回，并加强播放器转场期间的返回处理，避免穿透退出。
- 开启通知歌词后，媒体通知标题同时显示歌曲名与歌手，歌词独立显示；关闭后恢复常规媒体标题。

【本地歌曲切页】
- 修复从我的列表、发现、排行榜等页面切回本地歌曲时，数量已显示但列表短暂空白的问题；排序结果与字母索引跨主页面切换保留。
- 歌曲列表、数量、搜索与批量操作共用完整快照；首次整理完成前不再误报空库或无搜索结果，清空后重新入库不会闪回旧歌曲。

【数据保存与文件恢复】
- 统一收藏、历史记录和歌单修改的保存流程：写入成功后才更新界面状态，避免写盘失败后界面与重启结果不一致。
- 本地歌曲标签覆写前保存持久原件和恢复记录；进程中断后可在下次启动恢复，授权暂不可用时保留原件，等待重新授权重试。
- 本地歌曲索引改为原子写入成功后再更新内存，移除吞错和原位覆盖旧文件的备用路径；保留无变化时的快速返回与索引映射复用。
- 自定义目录未取得持久访问权限时，不保存目录、不切换扫描模式、不启动扫描，并给出明确提示。

【音源与播放稳定性】
- 为音源脚本的同步执行、Promise、HTTP、初始化、协议检查和状态读取统一执行时限，修复慢脚本不能及时取消的问题；超时或取消后运行时仍可继续使用。
- 修复 HTTP 请求的超时与取消上下文未正确传递到 QuickJS 执行线程的问题，并同步等待 Promise 任务退出，避免旧任务影响后续请求。
- 清空播放队列或移除最后一首歌曲时，统一取消旧歌词加载任务；播放服务断连后停止无效进度轮询，正常连接时保留暂停和后台刷新行为。

【质量验证】
- 补充写盘失败、进程中断恢复、目录授权、脚本超时取消及队列生命周期回归测试。
- 修正基准导航在侧栏动画期间点击旧坐标的问题，统一基准与性能配置采集的导航流程，并完成冷启动、主页面切换、详情往返三项实机基准验证。

### 提交追溯（安卓版）

> 本版本仅更新 Android 客户端至 0.1.6；Web / NAS、FPK 与 Docker 维持 0.1.1。完整提交区间：[android-v0.1.5...android-v0.1.6][Android 0.1.6]。

- 歌单来源、手动更新、防重与备份兼容，以及返回和通知交互：[`548e96d`](https://github.com/Raving4934/Melora/commit/548e96d3ab9d1dd9b27b0bc072dbc5162accfa00)。
- 歌单导入/更新弹窗、统一操作组与布局回归测试：[`460e2d9`](https://github.com/Raving4934/Melora/commit/460e2d933ef4cb4f9c7aa88a9d27a235759dc9b3)。
- 用户库保存事务：[`c6b546b`](https://github.com/Raving4934/Melora/commit/c6b546befcd259382f49cbbb59609267ce8b5cea)。
- 本地标签中断恢复：[`34725a1`](https://github.com/Raving4934/Melora/commit/34725a154dea706e400e86ce5425b8e7b2941919)。
- 本地索引原子保存：[`fc75cd6`](https://github.com/Raving4934/Melora/commit/fc75cd6750277a7dc77b59927b841662c2626f28)。
- 歌词与进度任务生命周期：[`7a1ad44`](https://github.com/Raving4934/Melora/commit/7a1ad44c73dcdc569ec1e88e65dd9796fb24c0ed)。
- QuickJS 统一时限与取消：[`bbd3e2e`](https://github.com/Raving4934/Melora/commit/bbd3e2ee36838505a14da175fe08ae9b66ed9000)。
- 目录持久授权失败处理：[`d4d044a`](https://github.com/Raving4934/Melora/commit/d4d044aa7b150512e6929daf1e734fd038f557c2)。
- 基准导航稳定性：[`1e23ca8`](https://github.com/Raving4934/Melora/commit/1e23ca8a8dfdbc2979fe95db31f7128565029481)。
- 本地歌曲切页快照与搜索准备态：[`42e1185`](https://github.com/Raving4934/Melora/commit/42e1185a655253432c16d4a3aba21e112a4dc44b)。

### 安卓安装包

- 正式发布后下载：[Android 0.1.6 APK（arm64-v8a）](https://github.com/Raving4934/Melora/releases/download/android-v0.1.6/melora-android-v0.1.6-arm64-v8a.apk)。
- 适用于 Android 8.0 及以上的 ARM64 设备；请使用正式签名安装包覆盖升级，无需卸载或清空数据。

## [0.1.5] - 2026-09-27

### 安卓客户端

【大屏导航】
- 平板及较宽窗口支持常驻侧栏，直接切换搜索、排行榜、发现、歌单、听书、本地歌曲、我的列表和设置；窄窗口继续使用抽屉导航。
- 侧栏入口支持滚动，大字体或较矮窗口中也能找到后面的页面；保留设置中的退出按钮开关。

【大屏播放器】
- 较宽窗口采用封面与阅读区双栏布局，右侧可在歌词和歌曲信息之间滑动切换；窄窗口保留原有分页布局。
- 沉浸播放适配双栏布局；大屏歌词区域滚动不再带动整张播放器下拉，封面区域仍可下拉收起。
- 平板竖屏的迷你歌词字号与可视空间自适应，减少控制区上方的多余空白，并对齐歌词设置与播放操作栏。

【卡片与布局】
- 排行榜按实际内容区宽度调整卡片列数，骨架与真实卡片保持一致；限制大屏听书快捷卡片尺寸，避免过度拉伸。
- 统一歌单、听书、搜索和发现网格的宽度计算，扣除常驻侧栏、页面边距及卡片间距，修复大屏内容区变窄后仍排过多列的问题；常见手机竖屏继续保持双列。

### 提交追溯（安卓版）

> 仅发布 Android 0.1.5；Web / NAS、FPK 与 Docker 维持 0.1.1。完整提交区间：[android-v0.1.4...android-v0.1.5][Android 0.1.5]。

- 常驻侧栏与宽窄窗口导航：[`bef0179`](https://github.com/Raving4934/Melora/commit/bef0179219ec5e16534449b90e5c62a2e1b588ec)。
- 大屏双栏播放器、歌词空间与手势：[`ced4872`](https://github.com/Raving4934/Melora/commit/ced48725d01ebd5b2ee53354ac643b95ce7369c1)。
- 榜单和听书快捷卡片尺寸：[`e0b1a26`](https://github.com/Raving4934/Melora/commit/e0b1a26904d707e4f07dc2994c4f1fd5d9b0f86a)、[`bbde6bc`](https://github.com/Raving4934/Melora/commit/bbde6bc40b05f1d9d8e575089511aff8df86cda8)。
- 网格实际内容宽度与回归测试：[`3b64b93`](https://github.com/Raving4934/Melora/commit/3b64b9341e1dd5ea6866c4641054e66d77c212af)。
- 独立 x86_64 模拟器 Debug 构建：[`73976a0`](https://github.com/Raving4934/Melora/commit/73976a0542a4b8a99b5df6a6591ca2b4baca2cef)。
- 安卓正式版发布后设为 Latest：[`bc5c431`](https://github.com/Raving4934/Melora/commit/bc5c431e3d64caa07041d1343085a2e6068e090a)。

### 安卓安装包

- [下载 Android 0.1.5 APK（arm64-v8a）](https://github.com/Raving4934/Melora/releases/download/android-v0.1.5/melora-android-v0.1.5-arm64-v8a.apk)
- 适用于 Android 8.0 及以上的 ARM64 设备；正式签名保持不变，可直接覆盖安装。

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
- 自动换源优先保留当前启用音源的可用结果；主源按阶梯降级后能播放时，不再为追逐备用高档音质增加等待。主源迟迟无结果或不可用时，仍会尝试备用音源。
- 区分播放与下载的音源选优缓存，避免下载选中的备用结果影响后续播放；下载保留按音质逐档选优，修复已失败资源重试时不必要的主源降级。
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
- 自动换源主源优先与播放／下载缓存隔离：[`c5b57e1`](https://github.com/Raving4934/Melora/commit/c5b57e102c4fe2a3edfc5af1e2872aee867ca4b0)。
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

[Unreleased]: https://github.com/Raving4934/Melora/compare/android-v0.1.8...HEAD
[Android Unreleased]: https://github.com/Raving4934/Melora/compare/android-v0.1.8...HEAD
[Web Unreleased]: https://github.com/Raving4934/Melora/compare/v0.1.1...HEAD
[0.1.8]: https://github.com/Raving4934/Melora/compare/android-v0.1.7...android-v0.1.8
[Android 0.1.8]: https://github.com/Raving4934/Melora/compare/android-v0.1.7...android-v0.1.8
[0.1.7]: https://github.com/Raving4934/Melora/compare/android-v0.1.6...android-v0.1.7
[Android 0.1.7]: https://github.com/Raving4934/Melora/compare/android-v0.1.6...android-v0.1.7
[0.1.6]: https://github.com/Raving4934/Melora/compare/android-v0.1.5...android-v0.1.6
[Android 0.1.6]: https://github.com/Raving4934/Melora/compare/android-v0.1.5...android-v0.1.6
[0.1.5]: https://github.com/Raving4934/Melora/compare/android-v0.1.4...android-v0.1.5
[Android 0.1.5]: https://github.com/Raving4934/Melora/compare/android-v0.1.4...android-v0.1.5
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
