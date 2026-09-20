package com.leyu.melora.playback.sdk

import com.leyu.melora.playback.lx.LxScript
import org.junit.Assert.assertEquals
import org.junit.Test

class LxScriptPoolPolicyTest {
    private val enabled = script("enabled.js", enabled = true)
    private val disabled = script("disabled.js", enabled = false)

    @Test
    fun enabledScopeDoesNotInitializeDisabledFallbacks() {
        assertEquals(listOf(enabled), LxScriptPool.scriptsInScope(listOf(disabled, enabled), LxScriptPool.ScriptScope.ENABLED))
    }

    @Test
    fun disabledScopeLoadsOnlyFallbackSources() {
        assertEquals(listOf(disabled), LxScriptPool.scriptsInScope(listOf(disabled, enabled), LxScriptPool.ScriptScope.DISABLED))
    }

    @Test
    fun allScopeKeepsEnabledSourcesFirst() {
        assertEquals(
            listOf(enabled, disabled),
            LxScriptPool.scriptsInScope(listOf(disabled, enabled), LxScriptPool.ScriptScope.ALL),
        )
    }

    private fun script(id: String, enabled: Boolean) = LxScript(
        id = id,
        name = id,
        description = "",
        version = "1",
        enabled = enabled,
    )
}
