package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Source DTO/admission cases only; not JNI, listener, protocol or worker proof. */
class SteerRouteBackendSpecTest {
    @Test fun privateCredentialsRoundtripAndRedact() {
        val a=SteerRouteBackendSpec.create();val b=SteerRouteBackendSpec.create()
        assertEquals(a.json().toString(),SteerRouteBackendSpec.parse(a.json().toString()).json().toString())
        assertNotEquals(a.username,b.username);assertNotEquals(a.password,b.password)
        assertFalse(a.toString().contains(a.username));assertFalse(a.toString().contains(a.password))
    }
    @Test fun noImplicitDnsTunOrCredentialTypeDowngrade() {
        val a=SteerRouteBackendSpec.create().json()
        for(field in listOf("tun","serverId","resolver","engine"))
            assertThrows(IllegalArgumentException::class.java){SteerRouteBackendSpec.parse(JSONObject(a.toString()).put(field,true).toString())}
        for(value in listOf<Any>(true,1,"", "short", "A".repeat(32)))
            assertThrows(IllegalArgumentException::class.java){SteerRouteBackendSpec.parse(JSONObject(a.toString()).put("username",value).toString())}
        assertThrows(IllegalArgumentException::class.java){SteerRouteBackendSpec.parse(JSONObject(a.toString()).put("destinationDns","system").toString())}
    }
    @Test fun listenerAckIsBoundedStrictAndNotConnectivity() {
        assertEquals(12345,SteerRouteBackendSpec.port("{\"schema\":1,\"port\":12345}"))
        for(port in listOf<Any>(0,1023,65536,"12345",1.5,true))
            assertThrows(IllegalArgumentException::class.java){SteerRouteBackendSpec.port(JSONObject().put("schema",1).put("port",port).toString())}
        assertThrows(IllegalArgumentException::class.java){SteerRouteBackendSpec.port("{\"schema\":1,\"port\":12345,\"connected\":true}")}
    }
    @Test fun newPrivateSlotsCannotAliasExistingVpnProbeOrWarpProcesses() {
        val workers=ClientPrimaryWorkerWire.routeWorkers
        assertEquals(8,workers.size);assertEquals(8,workers.toSet().size)
        assertEquals((0..7).map {":primaryRoute$it"},workers.map(ClientPrimaryWorkerWire::processSuffix))
        assertFalse(ClientPrimaryWorkerWire.isRouteWorker(ClientPrimaryWorkerService::class.java))
        assertFalse(ClientPrimaryWorkerWire.isRouteWorker(ClientPrimaryProbeWorkerService::class.java))
        assertFalse(ClientPrimaryWorkerWire.isRouteWorker(ClientPrimaryWarpWorkerService::class.java))
    }
    @Test fun oldModulesNeverInheritInternalBridgeCapability() {
        val caps=JSONObject().put("api_version",2).put("mcrf_snapshot_model",2)
        assertFalse(SteerSnapshotContract.parse(caps).internalRouteBackend)
        assertFalse(SteerSnapshotContract.parse(caps.put("route_backend",true)).internalRouteBackend)
        assertTrue(SteerSnapshotContract.parse(caps.put("route_backend",SteerRouteBackendSpec.CAPABILITY)).internalRouteBackend)
    }
}
