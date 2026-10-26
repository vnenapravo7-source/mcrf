package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DnsCarrierFailureTest {
    @Test fun dnsttLifecycleReasonsAreFiniteAndDoNotGuessWrongKey(){
        val detail=CarrierFailureDetail("dnstt",JSONObject())
        detail.offer("MCRF_ERROR DNSTT_NOISE_HANDSHAKE_FAILED")
        val message=detail.describe("запуск",1)
        assertTrue(message.contains("точная причина не подтверждена"))
        assertNull(DnsCarrierFailure.describe("dnstt","MCRF_ERROR DNSTT_SESSION_CLOSED private-fixture"))
        assertNull(DnsCarrierFailure.describe("vaydns","MCRF_ERROR DNSTT_SESSION_CLOSED"))
    }
    @Test fun knownRejectionRemainsVisibleWithoutRawPayload() {
        val detail=CarrierFailureDetail("vaydns",JSONObject())
        detail.offer("MCRF_ERROR REMOTE_SOCKS_AUTH_REJECTED: private-fixture-value")
        val message=detail.describe("запуск",1)
        assertTrue(message.contains("REMOTE_SOCKS_AUTH_REJECTED"))
        assertTrue(message.contains("попытки остановлены"))
        assertFalse(message.contains("private-fixture-value"))
    }
    @Test fun unknownControlMessagesRemainPrivate() {
        val detail=CarrierFailureDetail("vaydns",JSONObject())
        listOf("MCRF_ERROR FUTURE_UNKNOWN: fixture-secret", "MCRF_CONFIG error fixture-secret", "MCRF_ERROR CONFIG_READ fixture-secret").forEach(detail::offer)
        assertFalse(detail.describe("запуск",1).contains("fixture-secret"))
    }
    @Test fun vocabularyIsScopedToItsActualEngine() {
        assertNull(DnsCarrierFailure.describe("dnstt","MCRF_ERROR REMOTE_SOCKS_AUTH_REJECTED"))
        assertNull(DnsCarrierFailure.describe("vaydns","MCRF_ERROR "+"X".repeat(300)))
        assertNotNull(DnsCarrierFailure.describe("vaydns","MCRF_ERROR REMOTE_SOCKS_PROTOCOL_REQUIRED"))
        assertNotNull(DnsCarrierFailure.stage("vaydns","MCRF_STAGE session-reconnect"))
        assertNull(DnsCarrierFailure.stage("vaydns","MCRF_STAGE session-reconnect private-fixture-value"))
        assertNull(DnsCarrierFailure.stage("dnstt","MCRF_STAGE session-reconnect"))
    }
}
