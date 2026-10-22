package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AppPickerLoadGateTest {
    @Test fun onlyLatestLoadMayPopulateTheVisibleDialog(){
        val gate=AppPickerLoadGate();val first=gate.begin()!!;val second=gate.begin()!!
        assertFalse(gate.accepts(first));assertTrue(gate.accepts(second))
    }
    @Test fun dismissIsFinalAndLateResultsCannotReopenOrSave(){
        val gate=AppPickerLoadGate();val ticket=gate.begin()!!
        gate.close();assertFalse(gate.accepts(ticket));assertNull(gate.begin());gate.close();assertFalse(gate.accepts(ticket))
    }
    @Test fun firstPassLoadsOnlyUserAppsAndSelectedSystemApps(){
        assertTrue(AppPickerLoadGate.includes(false,false,false))
        assertTrue(AppPickerLoadGate.includes(true,true,false))
        assertFalse(AppPickerLoadGate.includes(true,false,false))
        assertTrue(AppPickerLoadGate.includes(true,false,true))
    }
    @Test fun arbitraryAndSupersededTicketsDoNotOwnTheDialog(){
        val gate=AppPickerLoadGate();assertFalse(gate.accepts(-1));assertFalse(gate.accepts(0));val ticket=gate.begin()!!
        assertFalse(gate.accepts(ticket+1));assertTrue(gate.accepts(ticket))
    }
}
