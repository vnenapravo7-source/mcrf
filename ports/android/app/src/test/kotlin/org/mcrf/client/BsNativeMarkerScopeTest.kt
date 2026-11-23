package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BsNativeMarkerScopeTest {
    @Test fun standaloneRouterDoesNotClaimServerHandshakeContract(){
        val data=BsNativeContract.routerMarkers.joinToString("\n").toByteArray()
        assertTrue(BsNativeContract.hasMarkers(ByteArrayInputStream(data),BsNativeContract.routerMarkers))
        assertFalse(BsNativeContract.hasMarkers(ByteArrayInputStream(data),BsNativeContract.markers))
    }
    @Test fun routeObservationAndAdmissionGuardsAreRequiredForBothKinds(){
        for(required in listOf("BS_NATIVE_FLOW_OBSERVATION_CONTRACT_1","BS_NATIVE_ADMISSION_PARENT_GUARD")){
            val data=BsNativeContract.routerMarkers.filterNot {it==required}.joinToString("\n").toByteArray()
            assertFalse(BsNativeContract.hasMarkers(ByteArrayInputStream(data),BsNativeContract.routerMarkers))
        }
    }
}
