package org.mcrf.client

import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test

class QuickTileAvailabilityTest {
    @Test fun explicitComponentStateOverridesManifestAndDisabledStatesStayDisabled() {
        for(manifest in listOf(true,false)) {
            assertTrue(QuickTileAvailability.available(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,manifest))
            for(state in listOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
                assertFalse(QuickTileAvailability.available(state,manifest))
            }
            assertEquals(manifest,QuickTileAvailability.available(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,manifest))
        }
    }
    @Test fun availabilityTargetsOnlyTheDeclaredTileServices() {
        assertEquals(setOf(VpnQuickTile::class.java,BsQuickTile::class.java,RulesQuickTile::class.java,AutoBsQuickTile::class.java,AnubisQuickTile::class.java),
            QuickTileAvailability.tiles.map {it.service}.toSet())
        assertEquals(5,QuickTileAvailability.tiles.size)
    }
}
