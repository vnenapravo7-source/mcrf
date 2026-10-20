package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierChallengeFlightsTest {
    @Test fun duplicateCannotCreateSecondWaiterOrReplayAfterRetirement() {
        val flights=CarrierChallengeFlights()
        val ticket=requireNotNull(flights.claim("id:owned"))
        assertNull(flights.claim("id:owned"))
        flights.retire(ticket)
        assertNull(flights.claim("id:owned"))
        assertEquals("ChallengeFlight(redacted)",ticket.toString())
    }
    @Test fun newNativeChildHasIndependentIdentity() {
        val first=CarrierChallengeFlights();val second=CarrierChallengeFlights()
        assertNotNull(first.claim("id:1"));assertNotNull(second.claim("id:1"))
    }
    @Test fun legacySameUrlWithFreshSessionIsNotDropped() {
        val first=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|manual|https://vk.com/captcha|owned-secret-one"))
        val repeated=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|manual|https://vk.com/captcha|owned-secret-one"))
        val renewed=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|manual|https://vk.com/captcha|owned-secret-two"))
        assertEquals(first.flightKey,repeated.flightKey);assertNotEquals(first.flightKey,renewed.flightKey)
        assertFalse(first.flightKey.contains("owned-secret"));assertFalse(first.toString().contains("vk.com"))
        val flights=CarrierChallengeFlights()
        assertNotNull(flights.claim(first.flightKey));assertNull(flights.claim(repeated.flightKey));assertNotNull(flights.claim(renewed.flightKey))
    }
    @Test fun explicitNativeIdWinsOverChangedEnvelopeContents() {
        val first=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|owned|manual|https://vk.com/captcha|one"))
        val repeated=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|owned|manual|https://vk.com/other|two"))
        assertEquals(first.flightKey,repeated.flightKey)
    }
    @Test fun historyIsBoundedAndStaleRetirementCannotRetireReplacement() {
        val flights=CarrierChallengeFlights(2)
        val old=requireNotNull(flights.claim("id:old"));flights.retire(old)
        repeat(2){flights.retire(requireNotNull(flights.claim("id:new-$it")))}
        val replacement=requireNotNull(flights.claim("id:old"))
        flights.retire(old)
        assertNull(flights.claim("id:old"))
        flights.retire(replacement);assertNull(flights.claim("id:old"))
    }
    @Test fun activeNativeSpamIsBoundedWithoutEvictingLiveWaiters() {
        val flights=CarrierChallengeFlights()
        val tickets=(0 until 32).map {requireNotNull(flights.claim("id:$it"))}
        assertThrows(CarrierChallengeFlights.CapacityExceeded::class.java){flights.claim("id:overflow")};assertNull(flights.claim("id:0"))
        flights.retire(tickets.first())
        assertNotNull(flights.claim("id:replacement"));assertNull(flights.claim("id:0"))
    }
}
