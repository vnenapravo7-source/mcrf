package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Source authored for the common P0 run; not executed per edit. */
class BsHotSelectionPolicyTest {
    @Test fun fasterLowerTierNeverDisplacesHealthyHigherTier(){
        for((higher,lower) in AutoSelection.ENGINE_PRIORITY.zipWithNext()){
            assertFalse(AutoSelection.prefer(lower,1,higher,1000,0))
            assertTrue(AutoSelection.prefer(higher,1000,lower,1,100))
        }
    }
    @Test fun sameTierRequiresStrictlyMoreThanConfiguredTolerance(){
        assertFalse(AutoSelection.prefer("csqtt",50,"csqtt",100,50))
        assertTrue(AutoSelection.prefer("csqtt",49,"csqtt",100,50))
        assertFalse(AutoSelection.prefer("csqtt",100,"csqtt",100,0))
    }
    @Test fun unavailableCandidateCannotBeSelectedAndDeadCurrentCanBeReplaced(){
        assertFalse(AutoSelection.prefer("csqtt",-1,"qwdtt",10,50))
        assertTrue(AutoSelection.prefer("wdtt",100,"csqtt",-1,50))
        assertFalse(AutoSelection.prefer("unknown",0,"csqtt",1,0))
    }
}
