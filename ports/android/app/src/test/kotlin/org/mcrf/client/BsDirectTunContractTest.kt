package org.mcrf.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BsDirectTunContractTest {
    @Test fun directTunRequiresExplicitVersionCapabilityAndMulticall() {
        for(version in listOf("swg-panel-2.5.2","swg-panel-2.5.3")) {
            assertTrue(BsDirectTunContract.admits(version,1,true))
            assertFalse(BsDirectTunContract.admits(version,0,true))
            assertFalse(BsDirectTunContract.admits(version,1,false))
        }
        for(version in listOf("","auto","swg-panel-2.5.1","2.5.3","csqtt20"))
            assertFalse(BsDirectTunContract.admits(version,1,true))
        assertTrue(BsDirectTunContract.admits("swg-panel-2.5.1",2,true))
        assertFalse(BsDirectTunContract.admits("swg-panel-2.5.1",2,false))
    }
}
