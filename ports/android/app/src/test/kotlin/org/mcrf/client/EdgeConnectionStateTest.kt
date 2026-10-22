package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.mcrf.client.EdgeConnectionState.Action.*

class EdgeConnectionStateTest {
    @Test fun pendingIndicatorUsesDestinationNotPreviousConnection() {
        for(target in listOf(false,true))for(previous in listOf(false,true))for(active in listOf(false,true)) {
            assertEquals(!target,EdgeConnectionState.indicator(false,active,true,previous,target))
            assertEquals(target,EdgeConnectionState.indicator(true,active,true,previous,target))
        }
    }
    @Test fun noPendingDestinationNeverInventsVpnOrKeepsStaleTarget() {
        for(section in listOf(false,true)) {
            assertFalse(EdgeConnectionState.indicator(section,false,true,false,null))
            assertEquals(section,EdgeConnectionState.indicator(section,true,false,true,false))
        }
    }
    @Test fun everyNewActivityStartsCollapsedRegardlessOfPreviousOpenPanel() {
        val first=EdgeConnectionState()
        assertTrue(first.collapsed)
        first.collapsed=false
        assertFalse(first.collapsed) // Same Activity keeps disclosure across render/navigation.
        assertTrue(EdgeConnectionState().collapsed) // No persisted expanded preference on reopen.
    }
    @Test fun exactlyOneSectionReflectsTheSingleActiveConnection() {
        for(active in listOf(false,true))for(white in listOf(false,true)) {
            assertEquals(active&&!white,EdgeConnectionState.checked(false,active,white))
            assertEquals(active&&white,EdgeConnectionState.checked(true,active,white))
        }
    }
    @Test fun explicitOppositeOnSwitchesButOppositeOffNeverStopsTheLiveSection() {
        for(section in listOf(false,true)) {
            assertEquals(SWITCH,EdgeConnectionState.action(section,true,true,!section,true))
            assertEquals(NONE,EdgeConnectionState.action(section,false,true,!section,true))
            assertEquals(NONE,EdgeConnectionState.action(section,true,true,section,true))
            assertEquals(STOP,EdgeConnectionState.action(section,false,true,section,true))
        }
    }
    @Test fun idleStartOnlyFromExplicitOnAndUnavailableControlsAreNoOps() {
        for(section in listOf(false,true))for(runningSection in listOf(false,true)) {
            assertEquals(START,EdgeConnectionState.action(section,true,false,runningSection,true))
            assertEquals(NONE,EdgeConnectionState.action(section,false,false,runningSection,true))
            for(active in listOf(false,true))for(wanted in listOf(false,true))
                assertEquals(NONE,EdgeConnectionState.action(section,wanted,active,runningSection,false))
        }
    }
}
