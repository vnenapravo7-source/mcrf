package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CarrierCsqttWaiterTest {
    // Rust registry: 128-bit session nonce followed by a nonwrapping64-bit counter.
    private val prefix="0123456789abcdef0123456789abcdef"
    private fun envelope(counter:String)=requireNotNull(CarrierChallenge.envelope(
        "CAPTCHA_SOLVE|$prefix$counter|auto|https://vk.com/captcha|fixture-session",true))
    @Test fun rust48HexWaiterSurvivesExactAndroidRoundtrip() {
        val native=envelope("0000000000000000")
        assertEquals(48,native.nativeId.length)
        assertEquals("CAPTCHA_RESULT|${native.nativeId}|fixture-proof\n",CarrierChallenge.command(native.nativeId,"fixture-proof"))
        assertEquals(9000L,CarrierChallenge.nativeTimeoutMillis("csqtt","auto",native.nativeId))
        assertEquals(59000L,CarrierChallenge.nativeTimeoutMillis("csqtt","manual",native.nativeId))
        assertEquals(119000L,CarrierChallenge.nativeTimeoutMillis("csqtt","selected",native.nativeId))
    }
    @Test fun repeatedOriginalEnvelopeUsesNewWaiterWithoutRetiredProofReplay() {
        val flights=CarrierChallengeFlights()
        val first=envelope("0000000000000000")
        val next=envelope("0000000000000001")
        val ticket=requireNotNull(flights.claim(first.flightKey))
        flights.retire(ticket)
        assertNull(flights.claim(first.flightKey))
        assertNotNull(flights.claim(next.flightKey))
        val delivered=mutableListOf<String>()
        val old=requireNotNull(CarrierChallenge.registerBounded(first.nativeId,first.url,{delivered.add("old:$it")}))
        CarrierChallenge.remove(old)
        val current=requireNotNull(CarrierChallenge.registerBounded(next.nativeId,next.url,{delivered.add("new:$it")}))
        try {
            CarrierChallenge.respond(old,"late-proof")
            assertTrue(delivered.isEmpty())
            CarrierChallenge.respond(current,"fixture-proof")
            CarrierChallenge.respond(current,"duplicate-proof")
            assertEquals(listOf("new:fixture-proof"),delivered)
        }finally {CarrierChallenge.remove(old);CarrierChallenge.remove(current)}
    }
    @Test fun unmodified25AndOldModulesRemainUntaggedNotInventedAsNewProtocol() {
        val original=requireNotNull(CarrierChallenge.envelope("CAPTCHA_SOLVE|auto|https://vk.com/captcha|fixture-session",true))
        assertEquals("",original.nativeId)
        assertEquals("CAPTCHA_RESULT|fixture-proof\n",CarrierChallenge.command(original.nativeId,"fixture-proof"))
        val flights=CarrierChallengeFlights()
        val ticket=requireNotNull(flights.claim(original.flightKey));flights.retire(ticket)
        assertNull(flights.claim(original.flightKey)) // Native2.5 limitation is not bypassed.
    }
}
