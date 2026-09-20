package com.leyu.melora.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 唯一预设目录与PCM处理状态；不再创建Android原生音效或控制播放器音量。 */
internal object AudioEffects {
    const val OFF = "off"
    // 曲线只表达音色，不在目录层叠加峰值归一化、负前级或第二套 BassBoost。
    // 引擎按实际滤波器频响一次校准宽带电平；峰值由末端声道联动限幅保护。
    data class Preset(
        val id: String,
        val label: String,
        val hint: String,
        val curve: List<Pair<Float, Float>> = emptyList(),
        val concert: Boolean = false,
    )

    val presets = listOf(
        Preset(OFF, "原声直出", "不附加均衡、低音与混响处理"),
        Preset("pop", "流行音乐", "饱满节奏与明亮高频", listOf(
            31f to 1.5f, 62f to 2f, 125f to 2.4f, 250f to 0.7f, 500f to -1.2f,
            1000f to -1f, 2000f to 0.5f, 4000f to 2.4f, 8000f to 3f, 16000f to 1f,
        )),
        Preset("vocal", "清澈人声", "减轻低频遮蔽，突出演唱细节", listOf(
            31f to -3.5f, 62f to -3f, 125f to -2.5f, 250f to -1f, 500f to 1f,
            1000f to 2.8f, 2000f to 3.8f, 4000f to 2.6f, 8000f to -0.8f, 16000f to -2f,
        )),
        Preset("speech", "听书增强", "削减低频轰鸣，突出对白与咬字", listOf(
            31f to -12f, 62f to -11f, 125f to -8f, 250f to -3f, 500f to 1f,
            1000f to 4.5f, 2000f to 3f, 4000f to -2.5f, 8000f to -8f, 16000f to -10f,
        )),
        Preset("rock", "摇滚重音", "强化鼓点冲击与吉他存在感", listOf(
            31f to 1f, 62f to 2f, 125f to 3.5f, 250f to 1f, 500f to 2.5f,
            1000f to 5.5f, 2000f to 1f, 4000f to 5f, 8000f to 1f, 16000f to -2f,
        )),
        Preset("concert", "现场大厅", "保留清晰原声，加入大厅空间与尾音", listOf(
            31f to -2f, 62f to -1.5f, 125f to -1f, 250f to -0.5f, 500f to 0f,
            1000f to 0.5f, 2000f to 0.5f, 4000f to 0f, 8000f to -0.5f, 16000f to -1.5f,
        ), concert = true),
        Preset("bass", "震撼重低音", "强化低频厚度与鼓点量感", listOf(
            31f to 2f, 62f to 4.5f, 125f to 6f, 250f to 5.5f, 500f to 1.5f,
            1000f to -2f, 2000f to -3f, 4000f to -1.5f, 8000f to -1f, 16000f to -2f,
        )),
    )
    val effectPresets = presets.filterNot { it.id == OFF }

    fun preset(id: String): Preset = presets.firstOrNull { it.id == id } ?: presets.first()
    fun restoreId(value: String?, fallback: String = OFF): String =
        presets.firstOrNull { it.id == value }?.id ?: preset(fallback).id

    enum class Phase { Idle, Applying, Applied, Failed }
    data class State(val presetId: String = OFF, val phase: Phase = Phase.Idle, val message: String? = null) {
        val active get() = phase == Phase.Applied && presetId != OFF
    }
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    fun statusText(selected: String, result: State): String = when {
        result.presetId != selected -> "等待应用所选预设"
        result.phase == Phase.Idle -> if (selected == OFF) "未启用音效，点击卡片开启" else "开始播放后应用"
        result.phase == Phase.Applying -> "正在应用…"
        result.phase == Phase.Failed -> result.message ?: "未能启用，请关闭后重试"
        result.message != null -> "已启用 · ${result.message}"
        selected == OFF -> "未启用音效，点击卡片开启"
        else -> "已启用，再次点击当前卡片关闭"
    }

    internal fun report(state: State) { mutableState.value = state }
    internal fun resetState() { mutableState.value = State() }
}
