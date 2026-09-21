package com.leyu.melora.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverCatalogTest {
    @Test fun allCatalogShortcutsOpenTheirExistingPages() {
        assertEquals(DiscoverCatalog.Playlists, DiscoverCatalog.fromTab(3))
        assertEquals(DiscoverCatalog.Leaderboards, DiscoverCatalog.fromTab(1))
        assertEquals(DiscoverCatalog.Audiobooks, DiscoverCatalog.fromTab(4))
        assertEquals(4, DiscoverCatalog.Audiobooks.tab)
        assertEquals(3, DiscoverCatalog.Playlists.tab)
        assertEquals(1, DiscoverCatalog.Leaderboards.tab)
    }

    @Test fun otherDiscoveryDestinationsKeepTheirExistingNavigation() {
        for (tab in listOf(-1, 0, 2, 5, 6, 7, 8)) assertNull(DiscoverCatalog.fromTab(tab))
    }
}
