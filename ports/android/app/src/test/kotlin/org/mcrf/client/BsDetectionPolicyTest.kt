package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.mcrf.client.DirectAccessCheck.Outcome.*

/** Queued with whole P0; authorship is not test execution/evidence. */
class BsDetectionPolicyTest {
    @Test fun disabledNeverSwitchesEitherWay(){
        val blocked=DirectAccessCheck.Result(NO_RESPONSE,NO_RESPONSE)
        val accessible=DirectAccessCheck.Result(RESPONSE,RESPONSE)
        assertFalse(blocked.shouldSwitch(false,true,true));assertFalse(accessible.shouldReturn(false,false))
    }
    @Test fun noNetworkAndCancelledAreNotEvidenceOfWhitelist(){
        for(value in listOf(NO_NETWORK,CANCELLED,RESTRICTED)){
            val unknown=DirectAccessCheck.Result(value,value,value)
            assertFalse(unknown.shouldSwitch(true,true,true));assertFalse(unknown.shouldReturn(true,false))
            for(other in listOf(RESPONSE,NO_RESPONSE)){
                val mixed=DirectAccessCheck.Result(other,value,NO_RESPONSE)
                val reversed=DirectAccessCheck.Result(value,other,NO_RESPONSE)
                assertFalse(mixed.shouldSwitch(true,true,true));assertFalse(mixed.shouldReturn(true,false))
                assertFalse(reversed.shouldSwitch(true,true,true));assertFalse(reversed.shouldReturn(true,false))
            }
        }
    }
    @Test fun tierSwitchNeedsBsServersAndVpnSection(){
        val blocked=DirectAccessCheck.Result(NO_RESPONSE,NO_RESPONSE)
        assertTrue(blocked.shouldSwitch(true,true,true))
        assertFalse(blocked.shouldSwitch(true,false,true));assertFalse(blocked.shouldSwitch(true,true,false))
    }
    @Test fun eitherHttpResponseRestoresVpnUnderTheSameDetectionPolicy(){
        for(result in listOf(DirectAccessCheck.Result(RESPONSE,NO_RESPONSE),DirectAccessCheck.Result(NO_RESPONSE,RESPONSE),DirectAccessCheck.Result(RESPONSE,RESPONSE))){
            assertTrue(result.shouldReturn(true,false));assertFalse(result.shouldReturn(true,true))
            assertFalse(result.shouldSwitch(true,true,true))
        }
    }
    @Test fun youtubeFailureOnVpnCanSelectBsEvenWithDirectResponses(){
        val result=DirectAccessCheck.Result(RESPONSE,RESPONSE,NO_RESPONSE)
        assertTrue(result.shouldSwitch(true,true,true));assertFalse(result.shouldSwitch(true,true,false))
    }
}
