package com.leyu.melora.playback

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PlayModeTest {
    @Test fun storageValuesRoundTripAndUnknownValuesUseTheGivenFallback() {
        for (mode in PlayMode.entries) assertEquals(mode, PlayMode.restore(mode.storageValue))
        assertEquals(PlayMode.List, PlayMode.restore(null))
        assertEquals(PlayMode.List, PlayMode.restore("future-mode"))
        assertEquals(PlayMode.Shuffle, PlayMode.restore("future-mode", PlayMode.Shuffle))
    }

    @Test fun cyclingKeepsTheExistingOrder() {
        assertEquals(PlayMode.Single, PlayMode.List.next())
        assertEquals(PlayMode.Shuffle, PlayMode.Single.next())
        assertEquals(PlayMode.List, PlayMode.Shuffle.next())
    }

    @Test fun applyingEveryModeReplacesBothFlagsWithoutTouchingSpeedOrQueue() {
        val setters = mutableListOf<Pair<String, Any>>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "setRepeatMode", "setShuffleModeEnabled" -> { setters += method.name to args!![0]; null }
                else -> error("模式应用不应触发${method.name}")
            }
        } as Player
        for ((mode, expected) in listOf(
            PlayMode.List to (Player.REPEAT_MODE_ALL to false),
            PlayMode.Single to (Player.REPEAT_MODE_ONE to false),
            PlayMode.Shuffle to (Player.REPEAT_MODE_ALL to true),
        )) {
            repeat(2) {
                setters.clear()
                player.applyPlayMode(mode)
                assertEquals(listOf("setRepeatMode" to expected.first, "setShuffleModeEnabled" to expected.second), setters)
            }
        }
    }
}
