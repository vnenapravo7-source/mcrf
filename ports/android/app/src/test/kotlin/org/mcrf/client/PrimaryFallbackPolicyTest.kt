package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class PrimaryFallbackPolicyTest {
    @Test fun replacementStartsOnlyAfterRetirementAndCancellationCheck() {
        val order=mutableListOf<String>()
        val result=PrimaryFallbackPolicy.handover({order+="retire"},{order+="check";false},{order+="start";42})
        assertEquals(42,result)
        assertEquals(listOf("retire","check","start"),order)
    }
    @Test fun pendingRetirementNeverStartsReplacement() {
        var started=false
        val original=IllegalStateException("owned retirement pending")
        val actual=assertThrows(IllegalStateException::class.java) {
            PrimaryFallbackPolicy.handover({throw original},{false},{started=true})
        }
        assertSame(original,actual);assertFalse(started)
    }
    @Test fun cancellationDuringRetirementPreventsReplacement() {
        var cancelled=false;var started=false
        assertThrows(IllegalStateException::class.java) {
            PrimaryFallbackPolicy.handover({cancelled=true},{cancelled},{started=true})
        }
        assertFalse(started)
    }
    @Test fun replacementFailureIsNotRetriedOrHidden() {
        var starts=0;var retires=0
        val original=IllegalArgumentException("incompatible settings")
        val actual=assertThrows(IllegalArgumentException::class.java) {
            PrimaryFallbackPolicy.handover({retires++},{false},{starts++;throw original})
        }
        assertSame(original,actual);assertEquals(1,starts);assertEquals(1,retires)
    }
    @Test fun explicitOptInSelectsOtherCoreOnly() {
        assertEquals("singbox",PrimaryFallbackPolicy.alternate("steer",true,false,false,false,false))
        assertEquals("steer",PrimaryFallbackPolicy.alternate("singbox",true,false,false,false,false))
    }
    @Test fun noRetryWithoutConsentOrAfterCancellation() {
        assertNull(PrimaryFallbackPolicy.alternate("steer",false,false,false,false,false))
        assertNull(PrimaryFallbackPolicy.alternate("steer",true,false,false,true,false))
        assertNull(PrimaryFallbackPolicy.alternate("steer",true,false,false,false,true))
    }
    @Test fun wireGuardAndFixturesNeverFallback() {
        for(primary in listOf("singbox","steer")) {
            assertNull(PrimaryFallbackPolicy.alternate(primary,true,true,false,false,false))
            assertNull(PrimaryFallbackPolicy.alternate(primary,true,false,true,false,false))
        }
    }
}
