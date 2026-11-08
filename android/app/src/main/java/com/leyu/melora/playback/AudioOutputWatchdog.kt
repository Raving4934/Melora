package com.leyu.melora.playback

/** 观察输出位置而非波形振幅；正常的静音PCM、静音音量和复用AudioTrack都算有效输出。 */
internal class AudioOutputWatchdog(private val timeoutMs: Long = 5_000L) {
    data class Observation(
        val uid: String?,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val outputGeneration: Long,
        val outputProgress: Long,
    )

    private var previous: Observation? = null
    private var startedAtMs = 0L
    private var startedPositionMs = 0L
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
        if (old == null || old.uid != observation.uid ||
            old.outputGeneration != observation.outputGeneration ||
            old.outputProgress != observation.outputProgress || observation.positionMs <= old.positionMs) {
            startedAtMs = nowMs
            startedPositionMs = observation.positionMs
            reported = false
            return false
        }
        if (!reported && nowMs - startedAtMs >= timeoutMs && observation.positionMs - startedPositionMs >= 1_000) {
            reported = true
            return true
        }
        return false
    }
}
