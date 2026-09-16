# 下载歌词与封面素材

`Fetcher(*catalog.Registry) download.MetadataFetcher` 只返回有界素材，不写文件、不解析音频，
不改变下载质量、后缀、目标路径或任务。公开 API 和 `MetadataAssets + error` 部分成功契约不变。

## 时间与部分成功

- 下载器外层仍保留既有 9 秒 fail-safe；本包不延长外层，也不让调用者使用父 context 已过期的结果。
- 内部最多 8 秒。若父 context 有 deadline，预算为
  `min(8s, remaining - min(1s, remaining/4))`，为父调用者预留余量。
- 歌词、封面并行；要求 getter 遵守传入 context。一个取消/失败时保留另一份已成功素材，
  同时返回非空 error。没有把 WaitGroup 改为不受控后台任务，也不承诺能终止忽略 context 的回调。
- 详情补全、封面请求和歌词处理共用同一个内部 deadline，不各自重新获得完整预算。
- 两类素材均未勾选且未启用 EmbedTags 时不发请求；EmbedTags 单独启用时仍获取两类嵌入素材。

## 参数与缺图补全

- 歌词调用 `registry.Lyrics(ctx, job.Track.ID)`，按完整规范 ID 分派；不传下载质量作为歌词 ID，
  不根据歌名或 `Track.ProviderID` 猜另一首歌。
- 封面优先使用任务的 `Track.CoverURL`。仅当它为空且规范 ID 非空时，最多一次
  `registry.Track(ctx, sameID)`；返回 ID 必须完全相同。该调用可能命中缓存，不强刷 catalog。
- 已有 URL 的任务不统一刷新；已有但失效/HTTP URL 也不启动跨平台或同名歌曲搜索。
- URL 仍经过 `catalog.NormalizeCoverURL`。已知故障 URL 归一化为空时不回退原地址。

## 安全和体积边界

- Broker 默认仅允许安全 HTTPS；不开放全局 HTTP、不盲升任意旧域名、不跳过 TLS。
- cover 请求参数对齐 Broker 的实际 5 秒上限，仍使用其公网/DNS/重定向限制。
- 图片仍限 1 MiB、JPEG/PNG、宽高各不超过 4096；按 `DecodeConfig` 的真实格式返回 MIME，
  不按 URL 扩展名猜图像类型，不把 GIF/WebP/SVG 或超限数据交给 tag 写入器。
  本轮未增加完整图像解码或转码。
- 歌词仍限 1 MiB，单行文本限 8192 字节、时间 0..86400 秒。跳过 NaN/Inf/无效时间，
  将控制字符及 Unicode 格式字符转换为空格，保持每行一条 LRC 记录。
- 下游下载器对 URL、凭据、路径文本等的更严格规则保持不变；本包不绕过这些校验。

## 内部诊断不等于 UI 呈现

`cover_http_not_allowed`、`cover_policy_rejected`、`cover_deadline`、`lyrics_deadline` 等
仅为本 Fetcher error 中的固定安全诊断，不包含原始 URL、token 或 getter 错误正文。
当前 `download.fetchAssets` 仍转为通用 `fetch` 及相应素材 warning；**UI 不直接显示这些 code**。
本包未增加下载共享字段、typed 对外错误或 API 字段。

## LX desktop 对照与限制

对照官方 `lyswhut/lx-music-desktop` commit `9c364b482e5621a1d38b50e8610d2fb974457e6e`：

- `src/renderer/store/download/action.ts:150` 的元数据流程分别捕获图片/歌词失败，再合并可用结果。
- 同文件缺少封面快照时调用封面获取流程；`src/renderer/core/music/download.ts:25` 还涉及本地/缓存素材。
- LX 的可选换源、歌词变体/缓存及 provider API 与本项目并不相同；本包不直接搬入跨平台匹配、
  明文请求或先定下载后缀的行为。

没有用户实际歌词/图片响应与时序，不能仅凭歌曲名和警告证明本次唯一真因。
`.audio` 0B、请求 FLAC 却实际 MP3、音频格式/质量与标签写入器其它失败不属于本包修复范围。
