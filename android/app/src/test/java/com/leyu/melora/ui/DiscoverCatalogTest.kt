package com.leyu.melora.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverCatalogTest {
    @Test fun playlistAndOfficialBoardShortcutsOpenTheExistingCatalogs() {
        assertEquals(DiscoverCatalog.Playlists, DiscoverCatalog.fromTab(3))
        assertEquals(DiscoverCatalog.Leaderboards, DiscoverCatalog.fromTab(1))
        assertEquals(3, DiscoverCatalog.Playlists.tab)
        assertEquals(1, DiscoverCatalog.Leaderboards.tab)
    }

    @Test fun otherDiscoveryDestinationsKeepTheirExistingNavigation() {
        for (tab in listOf(-1, 0, 2, 4, 5, 6, 7, 8)) assertNull(DiscoverCatalog.fromTab(tab))
    }
}
