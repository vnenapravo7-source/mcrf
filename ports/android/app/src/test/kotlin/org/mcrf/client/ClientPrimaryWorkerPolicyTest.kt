package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mcrf.engine.spi.v1.Engine

/** JVM-only admission checks. No fake engine, Binder, TUN, native validation or traffic claims. */
class ClientPrimaryWorkerPolicyTest {
    @Test fun bootstrapHasItsOwnSlotAndCannotRunPacketOrRouteCommands(){
        assertEquals(":primaryBootstrap",ClientPrimaryWorkerWire.processSuffix(ClientPrimaryBootstrapWorkerService::class.java))
        for(command in listOf("INIT","VALIDATE","START","STOP"))assertTrue(ClientPrimaryWorkerWire.bootstrapCommand(command))
        for(command in listOf(null,"","PLAN","PROBE_STEER","INIT_PACKET","START_PACKET","OPEN_ROUTE","OPEN_PACKET_ROUTE","ATTACH_ROUTE_PIPE","HEALTH"))
            assertFalse(ClientPrimaryWorkerWire.bootstrapCommand(command))
        assertFalse(ClientPrimaryWorkerWire.isRouteWorker(ClientPrimaryBootstrapWorkerService::class.java))
    }
    @Test fun separateValidationWorkerCannotRunProbeTunOrProtocolRoles(){
        assertEquals(":primaryValidation",ClientPrimaryWorkerWire.processSuffix(ClientPrimaryValidationWorkerService::class.java))
        for(command in listOf("INIT","VALIDATE","STOP"))assertTrue(ClientPrimaryWorkerWire.validationCommand(command))
        for(command in listOf(null,"","START","PLAN","PROBE_STEER","INIT_PACKET","START_PACKET","OPEN_ROUTE","OPEN_PACKET_ROUTE","ATTACH_ROUTE_PIPE","HEALTH"))
            assertFalse(ClientPrimaryWorkerWire.validationCommand(command))
    }
    @Test fun onewayMissingPidStillRequiresExactOwnerCapabilityAndGeneration() {
        fun allowed(pid:Int=0,owner:Int=42,oneway:Boolean=true,token:Boolean=true,gen:Long=7,expected:Long=7)=
            ClientPrimaryWorkerWire.commandOwner(pid,owner,oneway,token,gen,expected)
        assertTrue(allowed())
        assertTrue(allowed(pid=42,oneway=false))
        assertFalse(allowed(token=false))
        assertFalse(allowed(gen=8))
        assertFalse(allowed(gen=0,expected=0))
        assertFalse(allowed(owner=0))
        assertFalse(allowed(pid=0,oneway=false))
        assertFalse(allowed(pid=43))
    }

    @Test fun startAcknowledgementDoesNotPromoteSteerOrAcceptSingBoxStarting() {
        val singbox = ClientPrimaryCorePolicy.Core.SINGBOX
        val steer = ClientPrimaryCorePolicy.Core.STEER
        assertTrue(ClientPrimaryWorkerWire.acknowledgesStart(singbox, "RUNNING"))
        assertFalse(ClientPrimaryWorkerWire.acknowledgesStart(singbox, "STARTING"))
        assertTrue(ClientPrimaryWorkerWire.acknowledgesStart(steer, "STARTING"))
        assertTrue(ClientPrimaryWorkerWire.acknowledgesStart(steer, "RUNNING"))
        for (state in listOf(null, "", "ONLINE", "CONNECTED", "ERROR", "FAILED", "STOPPED", "WORKER_EXIT_REQUIRED")) {
            assertFalse(ClientPrimaryWorkerWire.acknowledgesStart(singbox, state))
            assertFalse(ClientPrimaryWorkerWire.acknowledgesStart(steer, state))
        }
    }

    @Test fun everyCoreModeHasAnExplicitTunOwnershipContract() {
        assertEquals(Engine.TunAcquisition.DEFERRED,
            ClientPrimaryWorkerWire.acquisition(ClientPrimaryCorePolicy.Core.SINGBOX, Engine.Mode.VPN))
        assertEquals(Engine.TunAcquisition.PROVIDED,
            ClientPrimaryWorkerWire.acquisition(ClientPrimaryCorePolicy.Core.STEER, Engine.Mode.VPN))
        for (core in ClientPrimaryCorePolicy.Core.values())
            assertEquals(Engine.TunAcquisition.NONE, ClientPrimaryWorkerWire.acquisition(core, Engine.Mode.PROXY))
    }

    @Test fun singBoxEnvelopeRefusesCoercionUnknownSettingsAndModeMismatch() {
        fun envelope() = JSONObject().put("schema", 1).put("mode", "vpn").put("metered", false).put("config", JSONObject())
        ClientPrimaryWorkerWire.checkSingBoxEnvelope(envelope().toString(), Engine.Mode.VPN)
        ClientPrimaryWorkerWire.checkSingBoxEnvelope(envelope().put("mode", "proxy").toString(), Engine.Mode.PROXY)
        for (bad in listOf(
            envelope().put("schema", "1"), envelope().put("schema", 2), envelope().put("schema", 1.5),
            envelope().put("metered", "false"), envelope().put("config", "{}"),
            envelope().put("config", JSONObject.NULL), envelope().put("mode", "proxy"),
            envelope().put("mode", "VPN"), envelope().put("unknown", true),
            envelope().apply { remove("metered") }
        )) reject { ClientPrimaryWorkerWire.checkSingBoxEnvelope(bad.toString(), Engine.Mode.VPN) }
        reject { ClientPrimaryWorkerWire.checkSingBoxEnvelope(" ".repeat(ClientPrimaryWorkerWire.MAX_INPUT + 1), Engine.Mode.VPN) }
    }

    @Test fun deferredRequestIsBoundToExactProfileSettingsAndCapabilities() {
        val profile = "test-profile"
        val settings = "{\"schema\":1}"
        val hash = ClientPrimaryWorkerWire.sha256("capabilities".toByteArray())
        val validation = Engine.Validation.deferredVpnValidated(profile, settings, hash)
        fun request(p: String = profile, s: String = settings, cap: String = hash, fd: Int = -1, handle: Long = 0) =
            Engine.Request(1, fd, s, p, "", "unused-by-pure-constructor", handle, emptyArray(), Engine.Mode.VPN,
                validation, Engine.TunAcquisition.DEFERRED, cap)
        assertEquals(-1, request().borrowedTunFd)
        reject { request(p = "$profile ") }
        reject { request(s = "$settings ") }
        reject { request(cap = "0".repeat(64)) }
        reject { request(fd = 0) }
        reject { request(handle = 1) }
        // PROVIDED never borrows a made-up -1 descriptor, including Steer.
        reject { Engine.Request(1, -1, settings, profile, "", "unused", 1, emptyArray(), Engine.Mode.VPN,
            Engine.Validation(true, "", ""), Engine.TunAcquisition.PROVIDED, null) }
    }

    @Test fun controlTextRefusesUnboundedOrLogBreakingValues() {
        assertEquals("group/tag", ClientPrimaryWorkerWire.smallString("group/tag", 512))
        for (value in listOf(null, "a\nb", "a\u0000b", "x".repeat(513)))
            reject { ClientPrimaryWorkerWire.smallString(value, 512) }
    }

    private inline fun reject(action: () -> Unit) {
        try { action() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        fail("Expected admission rejection")
    }
}
