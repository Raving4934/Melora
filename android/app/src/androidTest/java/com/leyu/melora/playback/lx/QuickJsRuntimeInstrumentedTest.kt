package com.leyu.melora.playback.lx

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quickjs.QuickJS
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickJsRuntimeInstrumentedTest {
    @Test
    fun evaluatesJavaScriptWithPackagedNativeRuntime() {
        QuickJS.createRuntimeWithEventQueue().use { runtime ->
            runtime.createContext().use { context ->
                assertEquals(42, context.executeIntegerScript("6 * 7", "native-smoke.js"))
            }
        }
    }
}
