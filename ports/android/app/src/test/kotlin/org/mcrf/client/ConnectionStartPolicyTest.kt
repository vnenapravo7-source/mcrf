package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test

class ConnectionStartPolicyTest {
    @Test fun ordinaryRunsDoNotDuplicateUiPreparation(){
        assertTrue(ConnectionStartPolicy.servicePrepares(false,false))
    }
    @Test fun diagnosticsAndUnfinishedMixedRoutesKeepTheirExactPreflight(){
        assertFalse(ConnectionStartPolicy.servicePrepares(true,false));assertFalse(ConnectionStartPolicy.servicePrepares(false,true))
    }
    @Test fun manualSteerDoesNotCreateAThrowawayNativeWorker(){
        assertTrue(ConnectionStartPolicy.skipTemporarySteerWorker(false,false))
        assertFalse(ConnectionStartPolicy.skipTemporarySteerWorker(true,false));assertFalse(ConnectionStartPolicy.skipTemporarySteerWorker(false,true))
    }
    @Test fun mixedSteerRequiresManualVpnBeforeAnyCandidateCanRun(){
        ConnectionStartPolicy.requireMixedSteerVpn(vpn=true,automatic=false)
        for((vpn,automatic) in listOf(true to true,false to false,false to true)){
            val error=assertThrows(ConnectionStartPolicy.MixedSteerRequiresManualVpn::class.java){
                ConnectionStartPolicy.requireMixedSteerVpn(vpn,automatic)
            }
            assertEquals("Смешанные правила Steer требуют ручного подключения в режиме VPN.",error.message)
        }
    }
}
