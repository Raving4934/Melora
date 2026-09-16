# 受控下载器

本包使用 Go 标准库和项目内的 `model`、`storage`、`audiotags`；不依赖外部标签库。
目标运行环境为 Go 1.26 / Linux（fnOS）。没有公开的自定义 Transport、DNS、
私网白名单或跳过 TLS 验证开关。测试依赖仅经未导出的构造器注入。

## 集成约束

- `New(root, concurrency, resolve, persist, initial)`：并发 0 按 1 处理，允许 1–3。
  根目录为空时可管理/查看历史记录，但 `Create` 和续传禁用。
- `SetOptions(settings)` 设置后续任务默认值；`CreateWithOptions` 仅覆盖本任务，不修改默认值。
  创建时固化 `fileNameFormat/writeLyrics/writeCover/embedTags`，以后改设置不改名、不重写历史文件。
  新任务忽略废弃的 `WriteMetadata` 设置，不生成面向用户的 JSON 元数据文件。
- `SetMetadataFetcher` 注入主线的歌词/封面获取器，在每次 worker 运行开始时固定回调。
  回调在 Manager 锁外执行，必须响应 context；下载器给出最多 9 秒素材预算，校验返回内容后才写入。
- API 必须先对用户选择的根目录进行授权。`ValidateRoot` 只验证已有绝对路径、
  每层无符号链接、可以写入并同步探针；它不是授权白名单。
- `Resolver` 必须尊重传入 context，返回授权资源的 HTTP/HTTPS 地址。每次自动重试或
  手动续传均重新解析 URL。解析地址仅驻留于本次请求，不进入任务、事件、日志或 sidecar。
- `Persister` 必须同步、原子保存单条记录，在有界时间内返回，且**不能重入 Manager**。
  回调按 Manager 锁串行调用；不能在回调内调用 `List`、`Action`、`Close` 等方法。
  `Create` 只有 queued 持久化成功后才确认并入队；状态保存失败会停止下载，而非伪称成功。
- `Updates` 立即发送完整快照，之后合并到最新快照。每个订阅者持有独立深拷贝。
  SSE 层应在请求结束时调用 unsubscribe。慢客户端不阻塞 worker。
- `Close` 幂等，等待 worker 停止、同步 part、保存暂停状态，然后关闭订阅和目录句柄。
  契约中的 `Close` 没有 error 返回值；回调/同步失败通过最终任务快照中的 Error 披露。
  无视 context 的 Resolver、永久阻塞的 Persister 或内核文件 I/O 仍会拖延 Close。

## 状态与恢复

主要成功路径：`queued → resolving → downloading → verifying → finalizing → completed`。
网络失败最多 3 次总尝试，等待状态为 `retry_wait`；401/403/404 为
`waiting_for_url_refresh`。启用歌词/封面/内嵌标签的新任务在验证后进入 `writing_metadata`，
再进入 `finalizing`。进度计数是原始下载字节，不把新增标签大小伪装为网络进度。

- pause：queued 或运行中任务转 paused；确认前等待真实文件 offset 保存，保留 part。
- resume：仅 paused → queued。
- retry：仅 failed → queued。
- cancel：非 completed 任务转 cancelled；先保存取消意图，再移除本任务 part、标签暂存和私有恢复检查点，
  不删除任何最终文件。清理失败返回错误，可重复 cancel，重启也会重做取消清理。
- 已完成任务拒绝上述动作。重启时所有非终态任务统一暂停，不自动联网。
- 旧任务保留原命名规则和 `WriteMetadata` 快照；只有旧任务会按原开关写 JSON。
  已完成历史音频启动时不改写；旧 JSON 路径若无法校验，只清除结果字段并给出警告。
- 标签发布成功但 completed 持久化失败时，以私有检查点的最终长度和 SHA-256 恢复，
  不重新下载/重打标签。歌词和封面分别保存 pending/written 意图与内容摘要，
  只认领匹配内容，不覆盖冲突文件；尚未发布且素材已变化时保守放弃。
- 恢复时内部顺序按 CreatedAt 实际时间升序、ID 升序统一排序，再反向生成 List/Updates，
  不依赖存储层输入顺序；相同时间的条目有稳定顺序，不因重启翻转。无效旧时间按零值处理。
- Configure 在 queued/运行中的任务存在时拒绝配置变更；完全相同配置为幂等操作。
  更改根后旧任务仍绑定原根；需恢复原授权根才可继续或清理旧任务，不将 part 偷移到新根。

## 网络与资源安全

| 限制 | 默认值 |
| --- | --- |
| 文件大小 | 2 GiB（含未知长度、chunked） |
| 并发 | 1，最高 3 |
| DNS 时间 / 地址个数 | 5 秒 / 64 |
| 所有候选地址合计连接时间 | 10 秒 |
| TLS 握手 / 响应头 | 10 秒 / 15 秒 |
| 连接读写空闲时间 | 30 秒 |
| 单次 HTTP 请求 / 单次任务运行总时间 | 10 分钟 / 30 分钟 |
| 自动尝试 / 退避 | 总共 3 次；1、2 秒 |
| 重定向 / 响应头大小 | 最多 5 跳 / 64 KiB |
| URL / 私有恢复检查点大小 | 8 KiB / 4 KiB |
| 歌词 / 封面 / 标签元数据上限 | 2 MiB / 10 MiB / 32 MiB |
| 封面像素 | 每边不超过 8192，总计不超过 3200 万 |
| 空间预留 / 重探测 | 16 MiB；每 1 MiB 或 1 秒，并逐块扣除已用量 |
| 进度持久化间隔 | 500 ms，停止/完成立即保存 |

保留来源明确给出的 HTTP/HTTPS，不猜测升级协议；HTTP 同样执行所有 SSRF 校验，拒绝 userinfo、fragment、局部域名、私网/回环/链路本地/保留 IPv4、
IPv4-mapped IPv6 以及保守 IPv6 特殊网段。IPv6 仅允许全球单播 2000::/3 中
非特殊用途地址。部分实际上可路由的特殊服务地址也被保守拒绝。
每次请求/每一跳重定向解析并验证**所有** DNS 地址，同时重新调用 `net.InterfaceAddrs`
拒绝命中本机任一网卡的地址（包括公网 IPv4/IPv6）；字面量 IP 也执行此检查。
本机地址枚举失败、为空或出现无法识别的地址时保守拒绝。网卡的 IPv4-mapped 表示
先归一化后比较，只拒绝本机 IP，不误封整个公网子网。通过检查后由 DialContext
连接固定的 IP，不二次 DNS 查询；仍用原主机名进行 TLS 证书验证。无环境代理，不转发
Authorization/Cookie/Referer，不接受透明解压。错误只使用预定义安全消息。HTTP 429 限流立即停止自动尝试并给出安全提示，不以 1/2 秒退避反复请求上游。

## 文件与 Range

文件位于授权根下的 `Singles/`，part 与最终文件在**同一个打开的目录**内：

- `.melora-{jobID}.part`：真实音频字节。
- `.melora-{jobID}.json`：私有续传校验器、长度、格式及完成恢复所需 SHA-256。
- `.melora-{jobID}.tags.part`：标签重写的独立暂存文件，失败不破坏原始 part。
- 新任务最终文件：`标题 - 歌手`、`歌手 - 标题` 或 `标题` + 实际格式扩展名。
  命名冲突时依次尝试 ` (2)`、` (3)`…` (10000)`，不再追加任务 ID。
  例如 `明天见 - 世界之外.flac`、`明天见 - 世界之外 (2).flac`。
  已预留短号仅在发生冲突时递增，不因队列排序、重启或较小编号空出而改名。
- 歌词 `.lrc`、封面 `.jpg/.png` 与最终音频使用相同 stem；冲突则保留已有文件并报告 warning。
- 旧无 `FileNameFormat` 任务仍用 `{清洗后歌手} - {清洗后标题} [{音质}] - {jobID}.{格式}`，
  并保留原碰撞失败契约。已有 `标题 - 歌手 (jobID)` 快照和完成文件继续原路径读取/恢复，不批量重命名。
  旧失败任务的 retry 不主动缩名或清空 part/checkpoint；只有尚未发布、且归档时发生真实冲突的
  非空 `FileNameFormat` 任务才转用数字编号。

“新任务不 JSON”指不再生成 `{stem}.json`；隐藏的 `.melora-{jobID}.json` 是必须保留的
内部续传/崩溃恢复检查点，不是内嵌标签替代品。检查点通过临时文件 + Sync + rename 原子更新，
只替换本任务私有路径；用户可见音频、歌词、封面及旧 JSON 均用不覆盖发布。

文件名不取自 URL 或 Content-Disposition；限制 UTF-8 字节长度，清理路径分隔符、
控制/方向字符和 Windows 保留名称。常规文件操作均经 `os.Root`；part 不接受符号链接、
硬链接、目录或特殊设备。Linux 最终发布使用同一个 Root 打开的目录 FD + 两个叶子名
调用 `renameat2(RENAME_NOREPLACE)`，不使用存在检查后普通 rename 的竞态实现。
不支持该系统调用或文件系统语义时安全失败，不降级为覆盖已有文件。
歌词/封面/旧 JSON 先写随机 O_EXCL 临时文件并 Sync，发布前后检查单链接常规文件与 inode，
再执行 NOREPLACE 和目录 Sync；不会用先判断存在再普通 rename 的方式发布。

Range 兼容依据 RFC 9110 §8.8、§13.1.5、§14.1/14.4、§15.3.7：

- 206 接受合法有界短分段，不要求本段终点就是 total−1。起点必须等于实际 offset，
  总长必须已知且在大小限制内；声明长度、实际收到字节、既有总长必须一致。
  仅 range unit 大小写不敏感（`bytes` / `Bytes`）；数字、分隔符、错位、多值不放宽。
- 继续拼接要求同一最终响应 URI 指纹及相同强 validator。指纹取最终 `resp.Request.URL`
  （排除片段）的 SHA-256，只写私有断点文件，不保存原 URL/token；ETag 不能跨 URI 证明对象相同。
- 强 ETag 按语法校验。Last-Modified 仅在没有 ETag，且响应 Date 至少晚 60 秒时作为
  保守候选；60 秒是本实现的时钟裕量，不是 RFC 9110 的固定要求。弱 ETag 不能改用日期绕过。
- 无可靠身份的首个短段、或资源 URI 改变时，不拼接；每轮最多一次显式 `Range: bytes=0-`
  独立替代。替代响应必须完整或取得可靠分段身份，否则固定错误终止。空间、响应头及魔数
  检查通过前保留旧 part。旧版缺少 URI 指纹的检查点仍可读，但只能从零请求独立对象。
- 每轮最多 128 个传输响应；外层最多 3 轮网络重试及既有总时限仍生效。不是无限循环；
  每个请求继续受既有最多 5 跳重定向限制。首段必须足以识别受支持的音频魔数，不猜格式。
- 200 无部分响应语义：无 Content-Range 时按完整响应从零替换；带该头时仅兼容
  `0..N−1/N` 的明确完整声明，并校验 Content-Length（若有）及实际完整 N 字节。
  部分、未知总长、畸形或重复范围信息仍拒绝；不把 200 当成可追加分段。
- 416 仅在本地总长、实际长度、资源 URI 指纹及强校验器一致时验证并收尾，
  否则有限重解析并重下；同样接受大小写混合的 `Bytes */N`。

范围诊断仅输出 HTTP 200/206/416 和固定原因码，例如 `range_offset_mismatch`、
`range_total_unknown`、`range_validator_mismatch`、`range_200_inconsistent`、
`range_identity_unconfirmed`、`range_request_limit`。`safeError` 保留这些受控分类，
不回显包装错误、原始 Content-Range、ETag、媒体 URL 或 token。
`.audio` 是魔数识别之前的暂定扩展名，不代表来源确实返回了某种音频。
没有用户真实响应头时，不能把所有 0B 失败归因于同一种响应模式。
MIME 白名单和文件魔数双检查，拒绝 HTML/JSON 错误页、未知格式、压缩响应。
无 Content-Type 或声明通用二进制时仍要求魔数与完整字节，不凭URL扩展或请求音质猜格式。
通用二进制支持 application/octet-stream、binary/octet-stream、application/x-octet-stream；
MP3 增加 audio/x-mp3、audio/mpeg3、audio/x-mpeg-3 别名，其它非音频类型不会全放行。
无效/重复的 Content-Type/Content-Encoding 仍拒绝。0.5.5起仅MP3/FLAC的音频MIME冲突可进入
额外内容核验：完整收齐后确认MP3三个连续完整帧，或FLAC STREAMINFO/元数据链/首帧字段及CRC8；
通过才按实际格式发布并提示media_mime_corrected。其它容器冲突仍拒绝，未知或明确非音频MIME不放行。
单个ID3标签按有界长度跳过再识别音频，大标签在前缀不足时暂留.audio；不在标签图片中搜索魔数。
私有reportedFormats仅保存最多六种格式类别，在连续Range中累积、独立替代时清空；不存原MIME/URL。
失败新增media_structure_invalid；结构确认不是完整音频解码/整帧CRC/来源认证。
ID3 body/FLAC元数据各32MiB上限、最多4096个FLAC块、每次探测读取不超过64字节。
前置ID3+FLAC的原字节保留；现有标签写入器若不支持该结构仍仅警告，不为兼容强行剥离标签。
音频检查失败按 media_mime_invalid / media_mime_unsupported / media_mime_mismatch /
media_encoding_unsupported / media_header_duplicate / media_not_audio / media_magic_unknown
分类；不回显原响应头/正文/URL。网页和JSON判断只用于错误归类，不把该判断当作音频解码。
最终同步文件并验证长度、格式、SHA-256，然后原子发布。SHA-256 是本地恢复校验，
不是来源真实性证明。保留完成 sidecar，用于 rename 成功但 completed 回调失败后的恢复；
不会把未知或被篡改的同名完成文件当作成功任务。

## 命名预留与恢复边界

- 创建和归档均在 Manager 锁内检查同根目录的全部未取消任务预留（包括 paused/failed/completed）；
  实际扩展识别前的 `.audio` 与 MP3/FLAC/OGG/M4A/AAC/WAV 共享同一个 stem 预留。
  新任务 queued 持久化成功后才入队；失败不会删除用户文件。取消仅释放内存预留，磁盘占位仍有效。
- 每轮选择只构造一次任务预留集；按编号逐叶 `Lstat` 固定目录 FD 下的受支持音频和
  LRC/JPG/PNG/JSON 名称。符号链接、目录、硬链接均算占用；不枚举目录、不递归、不给任意后缀扩权。
  每次创建最多 10000 个候选；归档保留当前名称，冲突后只向更大的短号推进，到上限安全失败并保留 part。
- 每个发布候选按 `saveMeta → finalizing 持久化 → 音频扩展复核/源文件身份复核 → renameNoReplace`
  执行。晚到的同名音频（包括不同扩展）会换号，音频发布始终使用内核原子不覆盖语义。
  发布窗口内才出现的歌词/封面冲突仍为“音频成功 + sidecar warning”，不会无限换号或覆盖 sidecar。
- checkpoint 的 `Final` 可能领先数据库中的 `TargetPath`；恢复只接受同任务基本名称、规范的
  `(2)..(10000)` 或该任务旧 ID 名，再按原长度/SHA-256/标签暂存证明恢复已发布文件。
  part 仍在时继续原有续传/校验流程；如领先短号已被另一个任务预留，再分配下一个空闲短号。
  音频与 sidecar 始终按最终 `TargetPath` 的 stem 绑定，不搬迁、清理或认领不匹配的用户文件。
- 预留互斥覆盖同一个 Manager 的队列；不是跨进程的多扩展文件系统事务。外部进程若在最后一次
  `Lstat` 之后创建不同扩展，可能出现同 stem 的多格式文件；每个实际发布目标仍不会被覆盖。
  不扩大目录授权或修改原有 FD 固定策略；生产目录应只允许可信写入者。

## 格式支持

| 音频 | 内嵌实现 | 保留音频方式 / 边界 |
| --- | --- | --- |
| MP3 | ID3v2.4：TIT2/TPE1/TALB、USLT、APIC | 接受无 ID3 或受支持的 ID3v2.3/v2.4；音频区逐字节复制。旧 v2.3 标签级 unsync、v2.4 帧级 unsync 可处理；扩展头、压缩、加密等不支持结构拒绝重写 |
| FLAC | Vorbis Comment（TITLE/ARTIST/ALBUM/LYRICS）+ PICTURE | STREAMINFO（含 MD5）和音频帧原样保留；移除 padding，保留非目标元数据 |
| M4A | iTunes ilst（标题/歌手/专辑/歌词/covr） | 非分片、单 moov/mdat 的 AAC 或 ALAC；moov 前置/后置、stco/co64 偏移修正；mdat 不变。分片、加密采样类型、多 mdat、嵌套 size=0 等不支持结构拒绝重写 |
| Ogg（含 Vorbis/Opus）、ADTS AAC、RIFF WAV | 暂不内嵌 | 可按现有 MIME/魔数策略下载并写歌词/封面；启用标签时给出不支持警告，保留原音频 |

标题、歌手、专辑、歌词和 JPEG/PNG 封面只在开启对应选项时获取或写入；不解码或转码音频。
标签失败不会丢弃已下载音频；`TagsWritten`、`LyricsPath`、`CoverPath` 只反映实际写入结果。
素材缺失/不安全、空间不足、写入失败均使用固定安全 warning，不包含原始 URL 或错误中的凭据。

## 已知边界

- 尚无速度限制或生产环境解码级音频验证。真实生成样本的 ffprobe/PCM 测试不是对任意损坏文件的解码保证。
- 当前主线 `downloadassets.Fetcher` 的歌词/封面限制更紧（各 1 MiB，封面每边 4096），
  封面仍走默认 HTTPS 元数据 Broker；允许公网 HTTP 的策略只覆盖用户主动下载音频，未扩大脚本/封面 HTTP 白名单。
- 普通 completed 历史条目的标签结果启动检查是文件类型/单链接/长度与检查点，
  不是全曲重新计算摘要；只有崩溃窗口最终恢复分支重新计算音频 SHA-256。
- 公网 HTTP 是用户来源明确指定的明文传输，不提供 HTTPS 的传输机密性或服务器证书认证。
- `os.Root` 不是 mount namespace；不能阻止管理员 bind mount 或同权限本地进程改写已授权目录。
  目录需仅对可信主体可写。远端 HTTP/DNS 和未授权路径不因此获得本地权限。
- 不能把数据库提交和文件系统 rename 合成一个事务；sidecar 对该崩溃窗口提供保守恢复。
  持久化后端持续失败时无法保证数据库已记录最新状态。
- 本机地址检查覆盖当前进程网络命名空间中可枚举的网卡；无法由网卡枚举发现
  外部 NAT 映射到本机的公网地址，容器外宿主机网卡也不可见。此类部署仍需额外出口防火墙策略。
- 已覆盖模拟低空间和 `/dev/full` 的真实 ENOSPC；持续掉电、真实磁盘耗尽、权限撤销、NFS/SMB 及 fnOS 真机存储行为未做故障注入验证；
  arm64 做交叉编译验证不等于真机运行验证。其他 OS 不提供不安全 rename 降级。
- 自动化测试使用内部受控 HTTP 替身及本地可信 TLS listener，不访问真实第三方签名资源。

## 验证

在 `apps/server` 下运行：

```sh
go test -race -p=1 -count=1 -timeout=180s ./internal/download ./internal/audiotags
go vet ./internal/download ./internal/audiotags
go test -p=1 -count=1 -cover ./internal/download ./internal/audiotags
```

测试包含公网地址策略、全 DNS 结果校验、固定拨号和总预算、真实 TLS、逐跳重定向、
无代理、Range 206/200/416/校验器、断流重试、大小/类型/总超时、路径逃逸/符号链接/
硬链接/不覆盖、恢复暂停、取消清理、持久化失败、完成崩溃窗口、并发状态和快照隔离。
命名专项覆盖创建顺序与重启短号稳定、全扩展占位、并发实际格式识别、晚到跨扩展冲突、
编号耗尽、固定 FD 路径替换、源文件身份替换、旧 ID 兼容、checkpoint 领先/短号被占用、
带/不带内嵌标签的归档崩溃恢复，以及 sidecar 同名绑定。可单独运行：
`go test -race -count=1 ./internal/download -run '^TestV10Naming'`。
另有标签失败保留原始音频、LRC/JPEG/PNG 并发原子发布、旧 JSON 兼容、新任务禁止用户 JSON、
ffmpeg 生成 MP3/FLAC/AAC/ALAC 样本的 ffprobe 标签/封面解析、编码帧及解码 PCM 完全相同验证。
ffmpeg/ffprobe 仅为测试可选工具，不是运行时依赖；工具缺失会 Skip，该情况下不得宣称通过实媒体验证。
