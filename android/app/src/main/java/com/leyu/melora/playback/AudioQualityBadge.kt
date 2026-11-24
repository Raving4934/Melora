package com.leyu.melora.playback

enum class AudioQualityTier(val label: String, val shortCode: String = label) {
    HiRes("HI-RES", "HR"), Lossless("LOSSLESS", "SQ"), Master("MASTER", "母带"),
    Atmos("ATMOS", "DA"), Spatial("3D SPATIAL", "3D"), Dsd("DSD"), High("HQ"),
    Standard("STANDARD", "SD"), Economy("ECO", "EC"), Low("LQ"),
    LoFi("LO-FI", "LF"), Mono("MONO", "MO"), Opus("OPUS"),
}

/** label 用于文字及图文模式。短规格仅在有对应依据时显示。 */
data class AudioQualityBadge(
    val tier: AudioQualityTier,
    val label: String = tier.label,
    val shortSpec: String? = null,
    val splitLabel: String = label,
    val compactTier: AudioQualityTier = tier,
) {
    val compactLabel: String get() = compactTier.shortCode
}

internal fun compressedAudioBadge(kbps: Int, codec: String? = null, channels: Int = -1): AudioQualityBadge? {
    if (kbps <= 0) return if (codec == "Opus") AudioQualityBadge(AudioQualityTier.Opus) else null
    val rate = "${kbps}K"
    val bitrateTier = when {
        kbps <= 32 && channels == 1 -> AudioQualityTier.Mono
        kbps <= 32 -> AudioQualityTier.LoFi
        kbps <= 64 -> AudioQualityTier.Low
        kbps <= 96 -> AudioQualityTier.Economy
        kbps >= 320 -> AudioQualityTier.High
        else -> AudioQualityTier.Standard
    }
    val tier = if (codec == "Opus") AudioQualityTier.Opus else bitrateTier
    val prefix = if (tier == AudioQualityTier.Standard) "SD" else tier.label
    val label = when {
        tier == AudioQualityTier.High -> "HQ"
        tier == AudioQualityTier.Standard && kbps != 128 -> rate
        else -> "$prefix $rate"
    }
    return AudioQualityBadge(tier, label, rate, prefix, compactTier = bitrateTier)
}
