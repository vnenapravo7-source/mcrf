package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Queued with whole P2 verification; no process or network actions. */
class RouteWorkerFailuresTest {
    @Test fun differentBackendDeathsAreReportedIndependentlyOnce() {
        val window=RouteWorkerFailures(listOf("singbox-route","xray-route","tls-route"))
        assertTrue(window.sample(setOf("singbox-route","xray-route","tls-route"),false).isEmpty())
        assertEquals(listOf("xray-route"),window.sample(setOf("singbox-route","tls-route"),false))
        assertTrue(window.sample(setOf("singbox-route","tls-route"),false).isEmpty())
        assertEquals(listOf("tls-route"),window.sample(setOf("singbox-route"),false))
        assertEquals(listOf("singbox-route"),window.sample(emptySet(),false))
    }
    @Test fun ordinaryRetirementIsNotAFalseServerFailure() {
        val window=RouteWorkerFailures(listOf("one","two"))
        assertTrue(window.sample(emptySet(),true).isEmpty())
        assertEquals(listOf("two"),window.sample(setOf("one"),false))
        assertTrue(window.sample(emptySet(),true).isEmpty())
    }
    @Test fun foreignDuplicateOrBlankWorkersAreRejectedAndInputIsCaptured() {
        assertThrows(IllegalArgumentException::class.java){RouteWorkerFailures(listOf("one","one"))}
        assertThrows(IllegalArgumentException::class.java){RouteWorkerFailures(listOf(""))}
        val tags=mutableListOf("one");val window=RouteWorkerFailures(tags);tags.add("foreign")
        assertThrows(IllegalArgumentException::class.java){window.sample(setOf("foreign"),false)}
        assertEquals(listOf("one"),window.sample(emptySet(),false))
    }
}
