package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ServerToolbarActionsTest {
    @Test fun bothSectionsOfferTheSameImportsAndUtilities() {
        val base=listOf(ServerToolbarActions.Action.CLIPBOARD,ServerToolbarActions.Action.QR,
            ServerToolbarActions.Action.PING,ServerToolbarActions.Action.REFRESH,
            ServerToolbarActions.Action.TELEGRAM,ServerToolbarActions.Action.VK)
        for(bs in listOf(false,true)) {
            assertEquals(base,ServerToolbarActions.order(bs,false))
            assertEquals(base+ServerToolbarActions.Action.WARP,ServerToolbarActions.order(bs,true))
        }
    }
    @Test fun restoringSelectedHiddenIdsPreservesOtherHiddenEntries() {
        assertEquals(setOf("other", "new"), ServerVisibility.merge(setOf("restore", "other"), setOf("new"), setOf("restore")))
        assertEquals(setOf("other"), ServerVisibility.merge(setOf("other"), emptySet(), setOf("absent")))
    }
}
