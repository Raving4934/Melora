package com.leyu.melora.playback

/** 观察输出位置而非波形振幅；正常的静音PCM、静音音量和复用AudioTrack都算有效输出。 */
internal class AudioOutputWatchdog(private val timeoutMs: Long = 5_000L) {
    data class Observation(
        val uid: String?,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val output: AudioOutputProbe.Snapshot,
    )

    private var previous: Observation? = null
    private var startedAtMs = 0L
    private var startedPositionMs = 0L
    var resumePositionMs = 0L
        private set
    private var reported = false

    fun reset() { previous = null; reported = false }

    fun update(observation: Observation, nowMs: Long): Boolean {
        val old = previous
        previous = observation
        val active = observation.playing && observation.uid != null &&
            (observation.durationMs <= 0 || observation.durationMs - observation.positionMs > 1_000)
        if (!active) {
            reset()
            return false
        }
        val newPosition = old == null || old.uid != observation.uid || observation.positionMs < old.positionMs - 1_000
        // 同一章节中更换音轨会重置帧数；只有新的播放位置或实际消费音频才能移动恢复点。
        if (newPosition || (observation.output.generation == old.output.generation &&
                observation.output.playedFrames > old.output.playedFrames)) {
            resumePositionMs = observation.positionMs
        }
        // 阶梯式媒体时钟中间可以连续几拍相同；只比较整个无输出窗口的净位移。
        if (newPosition || observation.output != old.output || observation.positionMs <= startedPositionMs) {
            startedAtMs = nowMs
            startedPositionMs = observation.positionMs
            reported = false
            return false
        }
        if (!reported && observation.positionMs > old.positionMs && nowMs - startedAtMs >= timeoutMs &&
            observation.positionMs - startedPositionMs >= 1_000) {
            reported = true
            return true
        }
        return false
    }
}
