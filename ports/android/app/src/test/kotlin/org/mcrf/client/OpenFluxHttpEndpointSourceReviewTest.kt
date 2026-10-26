package org.mcrf.client

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Queue/profile protocol models, NOT actual Android Service/Parcel/Binder. */
class OpenFluxHttpEndpointSourceReviewTest {
    private val child = "a".repeat(32); private val ticket = "b".repeat(32)
    private fun request(registry: OpenFluxVerificationRegistry, offer: (ByteArray) -> Boolean) =
        registry.registerHttp(child, OpenFluxHttpWire.Pending(ticket), { true }, offer)!!
    @Test fun defaultWriterRetainsLegacySixteenKiBCommandLimit() {
        val writer = CarrierIpcWriter(ByteArrayOutputStream())
        try {
            val tooLarge = ByteArray(16385) { 65 }; tooLarge[tooLarge.lastIndex] = 10
            try { writer.offer(tooLarge); fail("default widened") } catch (_: IllegalArgumentException) { }
        } finally { writer.requestStopDiscardingPending(); assertTrue(writer.awaitCompletion(5000).writerExited) }
    }
    @Test fun explicitHttpWriterAcceptsFullBoundIncludingNewlineAndStillWritesStop() {
        val output = ByteArrayOutputStream(); val writer = CarrierIpcWriter(output, maxCommandBytes = 131072)
        val bytes = ByteArray(131072) { 65 }; bytes[bytes.lastIndex] = 10
        try {
            assertTrue(writer.offer(bytes)); bytes.fill(0); writer.requestStop()
            val completion = writer.awaitCompletion(5000)
            assertTrue(completion.writerExited); assertTrue(completion.stopWritten); assertFalse(completion.failed)
            val written = output.toByteArray(); assertEquals(131077, written.size)
            assertEquals(65.toByte(), written[0]); assertEquals("STOP\n", written.copyOfRange(131072, written.size).toString(Charsets.US_ASCII))
        } finally { writer.requestStopDiscardingPending(); writer.awaitCompletion(5000) }
    }
    @Test fun offerRejectsWrongTicketRawCommandsAndEmbeddedLineBeforeCallback() {
        val registry = OpenFluxVerificationRegistry(); var offers = 0
        val request = request(registry) { offers++; true }; registry.claimBrowser(request.id)
        val window = OpenFluxWindowAdmission(); window.resume()
        val open = request.openHttpAfterUserAction(window, window.capture())!!
        for (line in listOf("STOP\n", "OPENFLUX_HTTP_OPEN|${"e".repeat(32)}|${"f".repeat(32)}\n",
            "OPENFLUX_HTTP_OPEN|$ticket|x\nSTOP\n", "OPENFLUX_CAPTCHA_REPLY|$ticket|x\n"))
            assertFalse(request.offerHttpCommand(line.toByteArray(Charsets.US_ASCII)))
        assertEquals(0, offers); assertTrue(request.offerHttpCommand(open)); assertEquals(1, offers); open.fill(0)
    }
    @Test fun ownerOrModelMustBeArmedAndCallbackFailureIsNotAcceptance() {
        val registry = OpenFluxVerificationRegistry(); var offers = 0
        val request = request(registry) { offers++; throw IllegalStateException("synthetic-private") }
        val command = "OPENFLUX_HTTP_OPEN|$ticket|${"c".repeat(32)}\n".toByteArray()
        assertFalse(request.offerHttpCommand(command)); registry.claimBrowser(request.id)
        assertFalse(request.offerHttpCommand(command)); assertEquals(0, offers)
        val window = OpenFluxWindowAdmission(); window.resume()
        val open = request.openHttpAfterUserAction(window, window.capture())!!
        assertFalse(request.offerHttpCommand(open)); assertEquals(1, offers); open.fill(0)
        registry.cancel(request.id)
    }
    @Test fun snapshotHasExplicitNotOpenOpeningReadyStatesWithoutPermitDisclosure() {
        val registry = OpenFluxVerificationRegistry(); val request = request(registry) { true }
        registry.claimBrowser(request.id); assertEquals(0, request.httpStatus().code)
        val window = OpenFluxWindowAdmission(); window.resume()
        val command = request.openHttpAfterUserAction(window, window.capture())!!
        assertEquals(1, request.httpStatus().code); assertNull(request.httpStatus().address)
        val nonce = command.toString(Charsets.US_ASCII).trim().substringAfterLast('|')
        val frame = OpenFluxHttpWire.OPENED + JSONObject().put("id", ticket).put("request", nonce)
            .put("permit", "d".repeat(32)).put("address", "http://127.0.0.1:4321/path")
        request.receiveHttp(child, frame.toByteArray())
        assertEquals(2, request.httpStatus().code); assertEquals("http://127.0.0.1:4321/path", request.httpStatus().address)
        assertEquals("OpenFluxHttpStatus(redacted)", request.httpStatus().toString()); command.fill(0)
    }
    @Test fun oldForegroundCannotOfferPreparedCommandAfterPauseOrRetirement() {
        assertFalse(OpenFluxHttpVerificationPolicy.productionEnabled)
        assertFalse(OpenFluxHttpVerificationPolicy.sourceCompatible(false, 1))
        assertFalse(OpenFluxHttpVerificationPolicy.sourceCompatible(true, 0))
        assertTrue(OpenFluxHttpVerificationPolicy.sourceCompatible(true, 1))
        val registry = OpenFluxVerificationRegistry(); var offers = 0
        val request = request(registry) { offers++; true }; registry.claimBrowser(request.id)
        val window = OpenFluxWindowAdmission(); window.resume()
        val command = request.openHttpAfterUserAction(window, window.capture())!!
        window.pause(); window.resume()
        assertEquals(-1, request.httpStatus().code); assertFalse(request.offerHttpCommand(command)); assertEquals(0, offers)
        registry.cancel(request.id); assertEquals(-1, request.httpStatus().code); assertFalse(request.offerHttpCommand(command))
        command.fill(0)
    }
}
