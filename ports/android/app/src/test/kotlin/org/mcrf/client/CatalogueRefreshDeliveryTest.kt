package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CatalogueRefreshDeliveryTest {
    private val none = CatalogueRefreshDelivery.Update.NONE
    private val servers = CatalogueRefreshDelivery.Update.SERVERS
    private val page = CatalogueRefreshDelivery.Update.PAGE

    @Test fun pausedOrBusyCompletionRemainsPendingUntilUiIsReady() {
        val delivery = CatalogueRefreshDelivery()
        delivery.changed(structureChanged = true, profileChanged = false)
        repeat(3) { assertEquals(none, delivery.take(ready = false, connectionPage = true)) }
        assertEquals(servers, delivery.take(ready = true, connectionPage = true))
        assertEquals(none, delivery.take(ready = true, connectionPage = true))
    }

    @Test fun navigatingAwayDoesNotLoseAServerInvalidation() {
        val delivery = CatalogueRefreshDelivery()
        delivery.changed(true, false)
        assertEquals(none, delivery.take(true, false))
        assertEquals(servers, delivery.take(true, true))
    }

    @Test fun profileSwitchRefreshesSettingsAsWellAsConnectionPages() {
        val delivery = CatalogueRefreshDelivery()
        delivery.changed(false, true)
        assertEquals(none, delivery.take(false, false))
        assertEquals(page, delivery.take(true, false))
        assertEquals(none, delivery.take(true, false))
    }

    @Test fun unchangedRefreshCannotClearAPendingStructuralChange() {
        val delivery = CatalogueRefreshDelivery()
        delivery.changed(true, false); delivery.changed(false, false)
        assertEquals(servers, delivery.take(true, true))
    }

    @Test fun coalescedServerUpdatesNeverDowngradeAProfileRefresh() {
        val delivery = CatalogueRefreshDelivery()
        delivery.changed(true, false); delivery.changed(false, true); delivery.changed(true, false)
        assertEquals(page, delivery.take(true, true))
        assertEquals(none, delivery.take(true, true))
    }

    @Test fun metadataOnlyChangesNeedNoHierarchyRebuild() {
        val delivery = CatalogueRefreshDelivery()
        delivery.changed(false, false)
        assertEquals(none, delivery.take(true, true))
    }
}
