package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Queued with ALL P1 changes; no Binder/process/socket/device execution. */
class ClientSteerStartMetadataTest {
    private fun settings(port: Int? = null) = JSONObject().put("steerVersion", 2).apply {
        if (port != null) put("localProxy", JSONObject().put("port", port))
    }
    private fun reason(port: Int) = JSONObject().put("schema", 1).put("localProxyPort", port).toString()
    @Test fun fixedAndOsAssignedPortsAreBoundToOriginalSettingsWithoutMutation() {
        listOf(2080, 0).forEach { requested ->
            val input = settings(requested); val before = input.toString()
            assertEquals(2080, ClientSteerStartMetadata.moduleReason(reason(2080), input))
            assertEquals(2080, ClientSteerStartMetadata.workerPort(2080, input))
            assertEquals(before, input.toString())
        }
    }
    @Test fun missingExtraCoercedAndUnexpectedListenerMetadataFailClosed() {
        listOf("", "{}", reason(2080).replace("2080", "\"2080\""),
            JSONObject(reason(2080)).put("extra", "not-logged").toString()).forEach { metadata ->
            assertThrows(Exception::class.java) { ClientSteerStartMetadata.moduleReason(metadata, settings(0)) }
        }
        listOf(null, "2080", 2080L, 0, 80, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { ClientSteerStartMetadata.workerPort(port, settings(0)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ClientSteerStartMetadata.moduleReason(reason(2080), settings()) }
        assertThrows(IllegalArgumentException::class.java) { ClientSteerStartMetadata.workerPort(2081, settings(2080)) }
    }
    @Test fun noListenerAndLegacyCannotInventAPortOrReuseV2Metadata() {
        assertEquals(0, ClientSteerStartMetadata.moduleReason(reason(0), settings()))
        assertEquals(0, ClientSteerStartMetadata.workerPort(0, settings()))
        val legacy = JSONObject()
        assertEquals(0, ClientSteerStartMetadata.moduleReason("", legacy))
        assertEquals(0, ClientSteerStartMetadata.workerPort(null, legacy))
        assertThrows(IllegalArgumentException::class.java) { ClientSteerStartMetadata.moduleReason(reason(0), legacy) }
        assertThrows(IllegalArgumentException::class.java) { ClientSteerStartMetadata.workerPort(2080, legacy) }
    }
    @Test fun unknownOrCoercedModelsNeverDowngradeToLegacy() {
        listOf("2", 2.0, 3, JSONObject.NULL).forEach { model ->
            assertThrows(IllegalArgumentException::class.java) {
                ClientSteerStartMetadata.workerPort(null, JSONObject().put("steerVersion", model))
            }
        }
    }
}
