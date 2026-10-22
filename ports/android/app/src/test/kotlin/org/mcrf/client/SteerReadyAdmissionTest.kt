package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SteerReadyAdmissionTest {
    @Test fun autoOffBeforePublicationCannotPromoteCandidate(){
        val gate=SteerReadyAdmission(true,true,1000,7)
        assertTrue(gate.ready(true,7,1100));assertFalse(gate.ready(false,7,1100))
    }
    @Test fun mutationOrExpiredOrOtherNetworkProofIsRefused(){
        for(gate in listOf(SteerReadyAdmission(false,true,1000,7),SteerReadyAdmission(true,false,1000,7)))assertFalse(gate.ready(true,7,1100))
        val gate=SteerReadyAdmission(true,true,1000,7)
        assertFalse(gate.ready(true,8,1100));assertFalse(gate.ready(true,null,1100));assertFalse(gate.ready(true,7,999));assertFalse(gate.ready(true,7,301001))
    }
}
