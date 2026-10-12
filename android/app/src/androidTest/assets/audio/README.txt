Generated silent test audio, no music recordings or third-party samples.
ffmpeg -f lavfi -i anullsrc=r=48000:cl=mono -t 1 -c:a flac -sample_fmt s16 -bits_per_raw_sample 16 fixture-16.flac
ffmpeg -f lavfi -i anullsrc=r=48000:cl=mono -t 1 -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 fixture-24.flac
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 1 -c:a libmp3lame -b:a 128k fixture-128.mp3
All files include metadata: title=Fixture, artist=Test Artist, album=Fixture Album.
Used only in the instrumentation APK for actual format/deduplication/publication tests.

fixture-320.mp3: 1 second of generated stereo silence, MP3 CBR 320kbps.
Generated locally with ffmpeg lavfi anullsrc and libmp3lame; no third-party audio.

AudioSchedulingInstrumentedTest 合成调度夹具（2026-10-12）
========================================================
仅使用本地 ffmpeg 的数学信号发生器，无真实歌曲、第三方录音、网络或用户库。
下列命令从仓库根目录执行；实际工具版本 ffmpeg/ffprobe 8.0.1-3ubuntu2+esm4，
libavcodec 62.11.100，libvorbis 编码器。所有新夹具都是 12 秒单声道。

生成命令（与此次执行一致）：

ffmpeg -hide_banner -loglevel error -f lavfi -i "aevalsrc=exprs='if(lt(mod(t,0.02),0.002),0.35*sin(2*PI*3500*t),0.015*sin(2*PI*330*t))':s=48000:d=12" -map_metadata -1 -c:a libvorbis -q:a 4 -iblock -15 -metadata title='Synthetic short-block scheduling fixture' -metadata artist='Melora instrumented tests' -y android/app/src/androidTest/assets/audio/scheduling-short-blocks.ogg

ffmpeg -hide_banner -loglevel error -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=12" -map_metadata -1 -c:a libmp3lame -b:a 128k -metadata title='Synthetic MP3 scheduling fixture' -metadata artist='Melora instrumented tests' -y android/app/src/androidTest/assets/audio/scheduling-12s.mp3

ffmpeg -hide_banner -loglevel error -f lavfi -i "aevalsrc=exprs='0.08*sin(2*PI*330*t)':s=48000:d=12" -map_metadata -1 -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 -metadata title='Synthetic FLAC scheduling fixture' -metadata artist='Melora instrumented tests' -y android/app/src/androidTest/assets/audio/scheduling-12s-24.flac

实测格式：
- scheduling-short-blocks.ogg：Vorbis，48000 Hz，214482 bytes。
- scheduling-12s.mp3：MP3 CBR 128 kbit/s，44100 Hz，193221 bytes。
- scheduling-12s-24.flac：FLAC 24-bit，48000 Hz，520365 bytes。

Vorbis 短帧分布（计数为 PCM 样本时长，不是压缩包的字节长度）：
- identification header 声明短/长 blocksize 分别为 256/2048 样本。
- ffprobe 的 4501 个音频包全部 duration=128 样本（2.666667ms），短时长占比 100%。
- 576/1024 样本包均为 0；首包 pts=-128，包含预滚，包时长总和 576128 样本。
- 解码后为 4500 帧，每帧 nb_samples=128，总共 576000 样本，恰好 12 秒。
- 每 20ms 的 2ms 高频脉冲配合 -iblock -15 触发密集短块，避免普通静音 Vorbis
  主要使用长块而不能复现密集解码唤醒的场景；MP3/FLAC 使用不同采样率验证换曲重配置。

包分布复核命令：

ffprobe -v error -select_streams a:0 -show_packets -show_entries packet=pts,duration,duration_time,size -of json android/app/src/androidTest/assets/audio/scheduling-short-blocks.ogg | python3 -c 'import collections,json,sys; packets=json.load(sys.stdin)["packets"]; durations=collections.Counter(int(p["duration"]) for p in packets); print("packets:",len(packets)); print("packet sample duration histogram:",dict(sorted(durations.items()))); print("sum durations:",sum(int(p["duration"]) for p in packets)); print("first packets:",packets[:3]); print("last packet:",packets[-1]); print("short packet fraction:",durations[128]/len(packets))'

ffprobe -v error -select_streams a:0 -show_frames -show_entries frame=nb_samples -of json android/app/src/androidTest/assets/audio/scheduling-short-blocks.ogg | python3 -c 'import collections,json,sys; frames=json.load(sys.stdin)["frames"]; c=collections.Counter(int(f["nb_samples"]) for f in frames); print("decoded frames:",len(frames)); print("decoded sample count histogram:",dict(sorted(c.items()))); print("decoded samples:",sum(int(f["nb_samples"]) for f in frames))'

当前夹具 SHA-256（Ogg 容器 serial 可能在重新生成时改变，重生成后重新实测并记录）：
ec930439e8eb5267cc3106e520e4af7c3c9ba363d1732801972a4949fa50a818  scheduling-short-blocks.ogg
09795dd93c8c2c3a5b23ab632354bcf16489564b65578859aeb6a1ea37fad08a  scheduling-12s.mp3
d437008a10b3991a04cb6ccff797dbfa35e40a156d754c52f7787443c82a3760  scheduling-12s-24.flac

隔离与判读：
- 必须使用项目现有 com.leyu.melora.IsolatedTestRunner；测试会确认是空 Application。
- 直接引用正式 PlaybackRenderersFactory，并启用 experimentalSetDynamicSchedulingEnabled(true)。
  测试不复制 renderer/audio sink、不连接 PlaybackService，不访问用户设置、库、手机目录或网络。
- 测试播放器 volume=0f；不改系统音量，不以模拟器 AudioTrack 是否有可听声音作为断言。
- 每个用例用独立 cache 子目录；finally 先 release 播放器，再删除自身的夹具目录。
- 高频采集的是未修饰的 ExoPlayer.currentPosition，尝试间隔 5ms；正式歌词 samplePosition
  按 500ms 发布，期间复用 lyricPositionAt / LyricTimeline / lyricWordProgress。
- await 只观察 ExoPlayer 状态和本地音效回报，不调用歌词 probe、不消费 reset；Seek/换曲
  的 reset 由真实 Player.Listener 不连续事件设置，下一次 capture 才消费。
- Log tag 为 AudioSchedulingTest：rawUpdates/rawForwardSteps 是原始 position 更新步数，
  rawJumpMaxMs/rawForwardJumpP95Ms 是原始位置跳幅，rawMaxObservedStallMs 是观测的最长不更新跨度，
  sampleGapP95Ms/sampleGapMaxMs 是实际采样间隔。停滞统计包含主机调度延迟，不能直接等同 renderer 耗时。
- lyricAheadMinMs/lyricAheadP95Ms/lyricAheadMaxMs 是“正式歌词插值位置 - 同时采得的原始 position”
  的有符号误差；没有把这项误差或任何帧耗时设成 CI 性能门禁。
- 歌词/逐字高亮的单调断言只证明歌词业务行为，不能证明原始 position 的旧 250ms 跳变已消失。
  需要主代理在实机/模拟器结合上述原始更新和误差日志判断调度回归。

建议由主代理顺序运行（本次未运行 Gradle/ADB）：
cd android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.leyu.melora.playback.AudioSchedulingInstrumentedTest
