package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AutoSelectionOffTest {
    @Test fun offRequiresAnExplicitAvailableManualTargetAndNeverFallsBack() {
        val pool=listOf("first","manual","third")
        assertEquals(listOf("manual"),BsAutoSelectionPolicy.targets(pool,"manual",BsAutoSelectionPolicy.OFF){it})
        assertTrue(BsAutoSelectionPolicy.targets(pool,"removed",BsAutoSelectionPolicy.OFF){it}.isEmpty())
        assertTrue(BsAutoSelectionPolicy.targets(pool,"",BsAutoSelectionPolicy.OFF){it}.isEmpty())
        assertTrue(BsAutoSelectionPolicy.targets(emptyList<String>(),"manual",BsAutoSelectionPolicy.OFF){it}.isEmpty())
    }
    @Test fun recentKeepsFallbackAndFastestKeepsMeasuredOrder() {
        val pool=listOf("fast","manual","slow")
        assertEquals(listOf("manual","fast","slow"),BsAutoSelectionPolicy.targets(pool,"manual",BsAutoSelectionPolicy.RECENT){it})
        assertEquals(pool,BsAutoSelectionPolicy.targets(pool,"removed",BsAutoSelectionPolicy.RECENT){it})
        assertEquals(pool,BsAutoSelectionPolicy.targets(pool,"manual",BsAutoSelectionPolicy.FASTEST){it})
        try{BsAutoSelectionPolicy.targets(pool,"manual","invalid"){it};fail("Unknown policy")}
        catch(_:IllegalArgumentException){}
    }
}
