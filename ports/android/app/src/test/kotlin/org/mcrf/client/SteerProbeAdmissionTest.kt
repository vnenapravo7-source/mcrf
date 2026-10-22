package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SteerProbeAdmissionTest {
    private fun base()=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2)
    @Test fun onlyExactNativeCapabilityAllowsIndependentProbes() {
        assertFalse(SteerSnapshotContract.parse(base()).independentProbe)
        for(value in listOf<Any>(true,1,"true","https","native_https_204_fresh_worker_v2"))
            assertFalse(SteerSnapshotContract.parse(base().put("independent_probe",value)).independentProbe)
        assertTrue(SteerSnapshotContract.parse(base().put("independent_probe",SteerProbeSpec.CAPABILITY)).independentProbe)
    }
    @Test fun privateProtocolBridgeRequiresItsOwnExactDeclaration(){
        assertFalse(SteerSnapshotContract.parse(base().put("independent_probe",SteerProbeSpec.CAPABILITY)).internalRouteBackend)
        for(value in listOf<Any>(true,1,"native_protocol_internal_socks_2"))assertFalse(SteerSnapshotContract.parse(base().put("route_backend",value)).internalRouteBackend)
        assertTrue(SteerSnapshotContract.parse(base().put("route_backend",SteerRouteBackendSpec.CAPABILITY)).internalRouteBackend)
        assertFalse(SteerSnapshotContract.parse(JSONObject().put("api_version",2).put("route_backend",SteerRouteBackendSpec.CAPABILITY)).internalRouteBackend)
    }
    @Test fun oldOrForeignModuleCannotInheritMainAppProbeSupport() {
        assertFalse(SteerSnapshotContract.parse(JSONObject().put("api_version",2).put("independent_probe",SteerProbeSpec.CAPABILITY)).independentProbe)
        assertThrows(IllegalArgumentException::class.java){SteerSnapshotContract.parse(base().put("api_version",1).put("independent_probe",SteerProbeSpec.CAPABILITY))}
    }
    @Test fun requestFingerprintBindsBoundariesProfileTargetAndNumericDial() {
        val original=SteerProbeSpec.requestSha("ab","c","192.0.2.1")
        assertNotEquals(original,SteerProbeSpec.requestSha("a","bc","192.0.2.1"))
        assertNotEquals(original,SteerProbeSpec.requestSha("ab","c","192.0.2.2"))
        assertNotEquals(original,SteerProbeSpec.requestSha("ab","d","192.0.2.1"))
        assertEquals(original,SteerProbeSpec.requestSha("ab","c","192.0.2.1"))
    }
}
