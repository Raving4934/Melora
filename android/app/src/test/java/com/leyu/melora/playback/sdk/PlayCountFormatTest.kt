package com.leyu.melora.playback.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayCountFormatTest {
    @Test
    fun plainNumbersAreConvertedByMagnitude() {
        assertEquals("1234", formatPlayCountLabel("1234"))
        assertEquals("3.6万", formatPlayCountLabel("36140"))
        assertEquals("3.6亿", formatPlayCountLabel("361400000"))
    }

    @Test
    fun oversizedWanStringsRollUpToYi() {
        // 源直接返回未换算的「万」级字符串：超过 10000 万应换算成亿
        assertEquals("36.1亿", formatPlayCountLabel("361400.0万"))
        assertEquals("3.7亿", formatPlayCountLabel("36692.9万"))
        assertEquals("9999.0万", formatPlayCountLabel("9999.0万"))
    }

    @Test
    fun existingUnitsArePreservedAndCleaned() {
        assertEquals("3.6亿", formatPlayCountLabel("3.6亿"))
        assertEquals("12.3万", formatPlayCountLabel("12.3万"))
    }

    @Test
    fun unparseableTextFallsBackToRaw() {
        assertEquals("", formatPlayCountLabel(""))
        assertEquals("—", formatPlayCountLabel("—"))
        assertEquals("暂无", formatPlayCountLabel("暂无"))
    }
}
