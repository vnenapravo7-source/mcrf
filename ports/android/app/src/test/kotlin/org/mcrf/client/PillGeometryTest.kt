package org.mcrf.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PillGeometryTest {
    private val screen = PillGeometry.Bounds(0, 24, 360, 752)

    @Test fun bottomNavigationOpensAboveTheSettingsButton() {
        val anchor = PillGeometry.Bounds(294, 688, 342, 736)
        assertEquals(PillGeometry.Placement(106, 536, 152),
            PillGeometry.place(screen, anchor, 248, 152, 48, 6, false))
    }

    @Test fun topNavigationOpensBelowTheSettingsButton() {
        val anchor = PillGeometry.Bounds(16, 32, 64, 80)
        assertEquals(PillGeometry.Placement(6, 80, 152),
            PillGeometry.place(screen, anchor, 248, 152, 48, 6, true))
    }

    @Test fun aShortWindowPlacesTheListBesideAnEdgeButton() {
        val window = PillGeometry.Bounds(70, 24, 440, 224)
        val anchor = PillGeometry.Bounds(388, 100, 436, 148)
        assertEquals(PillGeometry.Placement(140, 48, 152),
            PillGeometry.place(window, anchor, 248, 152, 48, 6, null))
    }

    @Test fun popupRequiresOneCompleteRow() {
        val window = PillGeometry.Bounds(0, 0, 140, 100)
        val anchor = PillGeometry.Bounds(46, 26, 94, 74)
        assertNull(PillGeometry.place(window, anchor, 120, 152, 48, 6, null))
    }
}
