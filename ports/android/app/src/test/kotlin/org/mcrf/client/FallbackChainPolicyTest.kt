package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class FallbackChainPolicyTest {
    private val ids = (1..6).map { it.toString(16).padStart(64, '0') }
    private fun next(policy: FallbackChainPolicy, current: String = ids[0], now: Long = 0,
                     chain: List<String> = ids, confirmed: Boolean = true, internet: Boolean = true,
                     automatic: Boolean = false, scope: String = "vpn", manual: String = ids[0], network: Long = 1) =
        policy.next(scope, chain, current, manual, network, confirmed, internet, automatic, now)

    @Test fun emptySingletonAndUnselectedChainsPreserveNormalBehavior() {
        assertNull(next(FallbackChainPolicy(), chain = emptyList()))
        assertNull(next(FallbackChainPolicy(), chain = listOf(ids[0])))
        assertNull(next(FallbackChainPolicy(), current = "other"))
    }
    @Test fun unconfirmedFailureOfflineAndAutomaticNeverSwitch() {
        assertNull(next(FallbackChainPolicy(), confirmed = false))
        assertNull(next(FallbackChainPolicy(), internet = false))
        assertNull(next(FallbackChainPolicy(), automatic = true))
    }
    @Test fun orderIsExplicitAndNeverWrapsBackToTheManualSelection() {
        val policy = FallbackChainPolicy()
        assertEquals(ids[3], next(policy, current = ids[2]))
        assertEquals(ids[4], next(policy, current = ids[3], now = 15_000))
        assertEquals(ids[5], next(policy, current = ids[4], now = 30_000))
        assertNull(next(policy, current = ids[5], now = 45_000))
    }
    @Test fun cooldownAndBudgetSurviveSuccessOrRepeatedFailure() {
        val policy = FallbackChainPolicy()
        assertEquals(ids[1], next(policy))
        assertNull(next(policy, current = ids[1], now = 14_999))
        assertNull(next(policy, current = ids[1], now = 15_000, confirmed = false))
        assertEquals(ids[2], next(policy, current = ids[1], now = 15_000))
        assertEquals(ids[3], next(policy, current = ids[2], now = 30_000))
        assertTrue(policy.exhausted(30_000))
        assertNull(next(policy, current = ids[3], now = 45_000))
        assertNull(next(policy, current = ids[3], now = 45_000, network = 2))
        assertNull(next(policy, current = ids[3], now = 200_000))
    }
    @Test fun expiryAndClockRollbackFailClosed() {
        val policy = FallbackChainPolicy()
        assertEquals(ids[1], next(policy, now = 50_000))
        assertNull(next(policy, current = ids[1], now = 49_999))
        assertNull(next(policy, current = ids[1], now = 230_001))
    }
    @Test fun onlyManualSelectionOrScopeChangeStartsAnIndependentEpisode() {
        val policy = FallbackChainPolicy()
        assertEquals(ids[1], next(policy))
        assertEquals(ids[2], next(policy, current = ids[1], manual = ids[1]))
        assertNull(next(policy, current = ids[1], manual = ids[1], network = 2))
        assertEquals(ids[2], next(policy, current = ids[1], manual = ids[1], network = 2, scope = "bs"))
    }
    @Test fun malformedDuplicateOrOversizeOrdersAreRejected() {
        assertTrue(FallbackChainPolicy.valid(ids))
        assertFalse(FallbackChainPolicy.valid(listOf(ids[0], ids[0])))
        assertFalse(FallbackChainPolicy.valid(listOf("https://private.example/token")))
        assertFalse(FallbackChainPolicy.valid((1..33).map { it.toString(16).padStart(64, '0') }))
    }
    @Test fun onlyTypedConnectionFailuresMayTriggerStartupFallback() {
        assertTrue(FallbackChainPolicy.confirmedConnectionFailure(ConnectException()))
        assertTrue(FallbackChainPolicy.confirmedConnectionFailure(java.io.IOException("wrapped", SocketTimeoutException())))
        assertFalse(FallbackChainPolicy.confirmedConnectionFailure(java.io.IOException("unknown failure")))
        assertFalse(FallbackChainPolicy.confirmedConnectionFailure(IllegalArgumentException("invalid", ConnectException())))
        assertFalse(FallbackChainPolicy.confirmedConnectionFailure(SecurityException("denied", SocketTimeoutException())))
        assertFalse(FallbackChainPolicy.confirmedConnectionFailure(java.util.concurrent.CancellationException()))
    }
}
