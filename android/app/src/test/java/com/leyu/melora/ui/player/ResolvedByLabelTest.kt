package com.leyu.melora.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedByLabelTest {
    @Test
    fun missingOrLegacyCacheIdentityIsNotPresentedAsBuiltin() {
        assertEquals("未知/缓存", resolvedByLabel(null))
        assertEquals("未知/缓存", resolvedByLabel("legacy"))
        assertEquals("未知/缓存", resolvedByLabel("unverified"))
    }

    @Test
    fun onlyKnownResourcePrefixesClaimAConcreteResolver() {
        assertEquals("未知音源", resolvedByLabel("retired-provider:cached-file"))
        assertEquals("未知音源", resolvedByLabel("resolver-without-display-name"))
        assertEquals("未知音源", resolvedByLabel("lx::hash"))
    }
    @Test
    fun localPlaybackDoesNotClaimAScript() {
        assertEquals("本地下载", resolvedByLabel("local"))
        assertEquals("本地媒体", resolvedByLabel("localmedia"))
    }

}
