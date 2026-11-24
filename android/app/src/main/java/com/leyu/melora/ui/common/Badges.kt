package com.leyu.melora.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.leyu.melora.playback.AudioQualityTier

/** 方案 A「金属铂银」。每档的柔雾底色使用该主题前景色和对应透明度。 */
internal enum class BadgePalette(lightRgb: Long, darkRgb: Long, val lightFillAlpha: Float, val darkFillAlpha: Float) {
    CopperRose(0xFF8F5140, 0xFFDFA08E, 0.11f, 0.15f),
    ChampagneGold(0xFF92641E, 0xFFDFB978, 0.11f, 0.15f),
    Platinum(0xFF2F4256, 0xFFC9D6E4, 0.11f, 0.15f),
    IndigoTitanium(0xFF485478, 0xFFA5B3DC, 0.10f, 0.14f),
    Slate(0xFF5A6068, 0xFF9BA1A9, 0.09f, 0.13f),
    Mist(0xFF757B84, 0xFF7C828B, 0.075f, 0.11f),
    PaleMist(0xFF848A92, 0xFF6E747D, 0.068f, 0.10f),
    MutedGray(0xFF92979F, 0xFF626770, 0.06f, 0.09f),
    WarmTaupe(0xFF8A7C71, 0xFF8B7E74, 0.075f, 0.105f),
    SoftGray(0xFF9BA0A7, 0xFF5A5F68, 0.055f, 0.085f);

    val light = Color(lightRgb)
    val dark = Color(darkRgb)
}

internal data class AudioBadgeAppearance(val palette: BadgePalette, val icon: ImageVector)

internal fun audioBadgeAppearance(tier: AudioQualityTier): AudioBadgeAppearance = audioBadgeAppearances.getValue(tier)

private val audioBadgeAppearances by lazy {
    mapOf(
        AudioQualityTier.HiRes to appearance("HiRes", BadgePalette.ChampagneGold) {
            line("M2.2 6.8V9.2M4.8 4.4V11.6M7.4 2.8V13.2M10 5.6V10.4")
            fill("M12.7 3L13.2 4.5L14.7 5L13.2 5.5L12.7 7L12.2 5.5L10.7 5L12.2 4.5Z")
        },
        AudioQualityTier.Lossless to appearance("Lossless", BadgePalette.Platinum) {
            line("M1.8 8C3.2 3.8 5.2 3.8 6.6 8C8 12.2 10 12.2 11.4 8C12.2 5.6 13.2 5.2 14.2 6.8")
            line("M1.8 9.2C2.8 10.8 3.8 10.4 4.6 8C6 3.8 8 3.8 9.4 8C10.8 12.2 12.8 12.2 14.2 8", alpha = 0.56f)
        },
        AudioQualityTier.Master to appearance("Master", BadgePalette.CopperRose) {
            line("M7.4 7.2A2.6 2.6 0 1 0 2.2 7.2A2.6 2.6 0 1 0 7.4 7.2Z")
            line("M13.8 7.2A2.6 2.6 0 1 0 8.6 7.2A2.6 2.6 0 1 0 13.8 7.2Z")
            fill("M5.45 7.2A.65 .65 0 1 0 4.15 7.2A.65 .65 0 1 0 5.45 7.2Z")
            fill("M11.85 7.2A.65 .65 0 1 0 10.55 7.2A.65 .65 0 1 0 11.85 7.2Z")
            line("M4.8 9.8H11.2M3.2 12.4H12.8")
        },
        AudioQualityTier.Atmos to appearance("Atmos", BadgePalette.IndigoTitanium) {
            line("M2.2 4.2H4.1C5.9 4.2 7.1 5.6 7.1 8C7.1 10.4 5.9 11.8 4.1 11.8H2.2Z")
            line("M13.8 4.2H11.9C10.1 4.2 8.9 5.6 8.9 8C8.9 10.4 10.1 11.8 11.9 11.8H13.8Z")
        },
        AudioQualityTier.Spatial to appearance("Spatial", BadgePalette.IndigoTitanium) {
            line("M2.2 9.89A6.1 2.75 -18 0 1 13.8 6.11A6.1 2.75 -18 0 1 2.2 9.89Z")
            fill("M9.45 8A1.45 1.45 0 1 0 6.55 8A1.45 1.45 0 1 0 9.45 8Z")
            line("M8 2.4V4.1M8 11.9V13.6", alpha = 0.65f)
        },
        AudioQualityTier.Dsd to appearance("Dsd", BadgePalette.CopperRose) {
            line("M2 7V9M4 5.2V10.8M6 3.5V12.5M8 2.6V13.4M10 3.5V12.5M12 5.2V10.8M14 7V9")
        },
        AudioQualityTier.High to appearance("High", BadgePalette.Slate) {
            line("M13.6 8A5.6 5.6 0 1 0 2.4 8A5.6 5.6 0 1 0 13.6 8Z")
            line("M9.75 8A1.75 1.75 0 1 0 6.25 8A1.75 1.75 0 1 0 9.75 8Z")
            line("M10.4 5.6L11.6 4.4", alpha = 0.72f)
        },
        AudioQualityTier.Standard to appearance("Standard", BadgePalette.Mist) {
            line("M4.2 6.4V9.6M8 4.4V11.6M11.8 6.4V9.6")
        },
        AudioQualityTier.Economy to appearance("Economy", BadgePalette.PaleMist) {
            line("M2.2 5.8L4.4 8L2.2 10.2M13.8 5.8L11.6 8L13.8 10.2")
            line("M6.7 5.5V10.5M9.3 6.5V9.5")
        },
        AudioQualityTier.Low to appearance("Low", BadgePalette.MutedGray) {
            line("M2 9.8H4.5V6H7.8V10.8H11V7.2H14")
        },
        AudioQualityTier.LoFi to appearance("LoFi", BadgePalette.WarmTaupe) {
            line("M3.8 4.2H12.2Q14 4.2 14 6V10Q14 11.8 12.2 11.8H3.8Q2 11.8 2 10V6Q2 4.2 3.8 4.2Z")
            line("M6.85 8A1.25 1.25 0 1 0 4.35 8A1.25 1.25 0 1 0 6.85 8Z")
            line("M11.65 8A1.25 1.25 0 1 0 9.15 8A1.25 1.25 0 1 0 11.65 8Z")
            line("M6.85 8H9.15", alpha = 0.65f)
        },
        AudioQualityTier.Mono to appearance("Mono", BadgePalette.SoftGray) {
            fill("M9.65 8A1.65 1.65 0 1 0 6.35 8A1.65 1.65 0 1 0 9.65 8Z")
            line("M12.6 8A4.6 4.6 0 0 1 11.96 10.34M10.05 12.12A4.6 4.6 0 0 1 7.69 12.59M5.49 11.85A4.6 4.6 0 0 1 3.89 10.07")
            line("M3.41 7.75A4.6 4.6 0 0 1 4.17 5.45M5.96 3.88A4.6 4.6 0 0 1 8.34 3.41M10.59 4.19A4.6 4.6 0 0 1 12.13 5.98")
        },
        AudioQualityTier.Opus to appearance("Opus", BadgePalette.Mist) {
            line("M2.5 5.4H12.2M10.2 3.4L12.4 5.4L10.2 7.4")
            line("M13.5 10.6H3.8M5.8 8.6L3.6 10.6L5.8 12.6", alpha = 0.72f)
        },
    )
}

private fun appearance(
    name: String,
    palette: BadgePalette,
    paths: ImageVector.Builder.() -> Unit,
) = AudioBadgeAppearance(
    palette = palette,
    icon = ImageVector.Builder(name, 16.dp, 16.dp, 16f, 16f).apply(paths).build(),
)

private fun ImageVector.Builder.line(data: String, alpha: Float = 1f) {
    addPath(
        pathData = addPathNodes(data),
        stroke = SolidColor(Color.White),
        strokeAlpha = alpha,
        strokeLineWidth = 1.4f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

private fun ImageVector.Builder.fill(data: String) {
    addPath(pathData = addPathNodes(data), fill = SolidColor(Color.White))
}
