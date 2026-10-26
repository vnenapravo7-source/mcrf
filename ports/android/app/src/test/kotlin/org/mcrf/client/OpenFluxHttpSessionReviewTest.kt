package org.mcrf.client

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Protocol models only: no real window, Binder, child, script, HTTP or renderer. */
class OpenFluxHttpSessionReviewTest {
    private class Fixture {
        val child = "a".repeat(32); val ticket = "b".repeat(32); val permit = "d".repeat(32)
        val address = "http://127.0.0.1:4321/path"
        val window = OpenFluxWindowAdmission()
        var now = 0L; var alive = true; var serial = 0
        val session = OpenFluxHttpSession(child, ticket, 1_000_000L, { alive }, window, { now }, { "%032x".format(++serial) })
        var opening: String? = null
        fun open() {
            window.resume(); opening = session.openAfterUserAction(window.capture())!!.substringAfterLast('|')
        }
        fun opened(): ByteArray = frame(OpenFluxHttpWire.OPENED, JSONObject().put("id", ticket).put("request", opening)
            .put("permit", permit).put("address", address))
        fun ready() { open(); assertEquals(OpenFluxHttpSession.Received.CONSUMED, session.receive(child, opened())) }
        fun begin(): String { val id = session.enqueue("GET", address, emptyList(), byteArrayOf())!!; assertNotNull(session.nextCommand()); return id }
        fun meta(id: String, body: ByteArray, hash: String = digest(body)): ByteArray =
            frame(OpenFluxHttpWire.META, JSONObject().put("id", ticket).put("request", id).put("status", 200)
                .put("headers", JSONArray()).put("bytes", body.size).put("sha256", hash))
        fun chunk(id: String, offset: Int, body: ByteArray): ByteArray = frame(OpenFluxHttpWire.CHUNK,
            JSONObject().put("id", ticket).put("request", id).put("offset", offset).put("data", Base64.getEncoder().encodeToString(body)))
        fun end(id: String): ByteArray = frame(OpenFluxHttpWire.END, JSONObject().put("id", ticket).put("request", id))
        fun complete(id: String, body: ByteArray) {
            assertEquals(OpenFluxHttpSession.Received.CONSUMED, session.receive(child, meta(id, body)))
            var offset = 0
            while (offset < body.size) {
                assertEquals(OpenFluxHttpSession.Received.CONSUMED,
                    session.receive(child, chunk(id, offset, body.copyOfRange(offset, minOf(offset + 8192, body.size)))))
                offset += 8192
            }
            assertEquals(OpenFluxHttpSession.Received.COMPLETE, session.receive(child, end(id)))
        }
        fun initial() { ready(); val id = begin(); complete(id, byteArrayOf()); session.takeResponse()!!.close() }
    }
    @Test fun httpReplyRequiresInitialResponseOwnershipTransferNotJustEnd() {
        val f = Fixture(); f.ready()
        assertNull(f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}"))
        val id = f.begin(); f.complete(id, byteArrayOf())
        assertNull(f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}"))
        f.session.takeResponse()!!.close()
        assertNotNull(f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}"))
    }
    @Test fun httpReplyAdmissionIsOneShotAndNotNativeAck() {
        val f = Fixture(); f.initial()
        val command = f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}")!!
        val parts = command.split('|'); assertEquals(5, parts.size)
        assertEquals("OPENFLUX_HTTP_REPLY", parts[0]); assertEquals(f.ticket, parts[1]); assertEquals(f.permit, parts[2])
        assertEquals("{\"answer\":true}", String(Base64.getDecoder().decode(parts[4]), Charsets.UTF_8))
        assertEquals(1, f.session.replyStatus())
        assertNull(f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":false}"))
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf())); assertNull(f.session.nextCommand())
        val ack = frame(OpenFluxHttpWire.RESULT, JSONObject().put("id", f.ticket).put("request", parts[3]).put("code", "accepted"))
        assertEquals(OpenFluxHttpSession.Received.COMPLETE, f.session.receive(f.child, ack))
        assertEquals(2, f.session.replyStatus())
        assertEquals(OpenFluxHttpSession.Received.COMPLETE, f.session.receive(f.child, ack)); assertEquals(2, f.session.replyStatus())
    }
    @Test fun httpReplyWrongChildTicketOrNonceCannotProduceAck() {
        val f = Fixture(); f.initial()
        val nonce = f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}")!!.split('|')[3]
        fun ack(ticket: String, request: String) = frame(OpenFluxHttpWire.RESULT,
            JSONObject().put("id", ticket).put("request", request).put("code", "accepted"))
        f.session.receive("c".repeat(32), ack(f.ticket, nonce))
        f.session.receive(f.child, ack("c".repeat(32), nonce))
        f.session.receive(f.child, ack(f.ticket, "c".repeat(32)))
        assertEquals(1, f.session.replyStatus())
    }
    @Test fun httpReplyFailedNativeApplyIsTerminalAndNeverAccepted() {
        val f = Fixture(); f.initial()
        val nonce = f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}")!!.split('|')[3]
        assertEquals(OpenFluxHttpSession.Received.REJECTED, f.session.receive(f.child, frame(OpenFluxHttpWire.RESULT,
            JSONObject().put("id", f.ticket).put("request", nonce).put("code", "reply_failed"))))
        assertEquals(-1, f.session.replyStatus()); assertNull(f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}"))
        assertNotNull(f.session.takeCancelCommand())
    }
    @Test fun httpReplyNeedsExactManualForegroundAndCannotReviveAfterPause() {
        val f = Fixture(); f.initial(); val other = OpenFluxWindowAdmission(); other.resume()
        assertNull(f.session.replyAfterUserAction(null, "{\"answer\":true}"))
        assertNull(f.session.replyAfterUserAction(other.capture(), "{\"answer\":true}"))
        val old = f.window.capture(); f.window.pause(); f.window.resume()
        assertNull(f.session.replyAfterUserAction(old, "{\"answer\":true}")); assertEquals(-1, f.session.replyStatus())
    }
    @Test fun httpReplyShapeAndUtf8BudgetRejectWithoutConsumingAttempt() {
        val f = Fixture(); f.initial()
        for (bad in listOf("{}", "not-json", "{\"a\":\"" + "я".repeat(8192) + "\"}", "{\"a\":\"\uD800\"}"))
            assertNull(f.session.replyAfterUserAction(f.window.capture(), bad))
        assertEquals(0, f.session.replyStatus()); assertNotNull(f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}"))
    }
    @Test fun httpReplyNonceIsReservedAfterResourceRequestNonceBudgetExhausted() {
        val f = Fixture(); f.initial()
        repeat(58) {
            val id = f.begin(); f.complete(id, byteArrayOf()); f.session.takeResponse()!!.close()
        }
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf()))
        assertNotNull(f.session.replyAfterUserAction(f.window.capture(), "{\"answer\":true}"))
        assertEquals(1, f.session.replyStatus())
    }
    @Test fun manualOpenRequiresExactForegroundEpochAndIsOneAttempt() {
        val f = Fixture()
        assertEquals(OpenFluxHttpSession.Received.CONSUMED, f.session.receive(f.child,
            frame(OpenFluxHttpWire.PENDING, JSONObject().put("id", f.ticket).put("kind", "own_http"))))
        assertNull(f.session.openAfterUserAction(null))
        val other = OpenFluxWindowAdmission(); other.resume()
        assertNull(f.session.openAfterUserAction(other.capture()))
        f.open(); assertNull(f.session.openAfterUserAction(f.window.capture()))
        f.window.pause(); f.window.resume()
        assertEquals(OpenFluxHttpSession.Received.CONSUMED, f.session.receive(f.child, f.opened()))
        assertNotNull(f.session.takeCancelCommand()); assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf()))
    }
    @Test fun openedMustMatchChildTicketAndExactManualAttemptBeforeAnyRequest() {
        val f = Fixture(); f.open()
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf()))
        assertEquals(OpenFluxHttpSession.Received.CONSUMED, f.session.receive("e".repeat(32), f.opened()))
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf()))
        assertEquals(OpenFluxHttpSession.Received.CONSUMED, f.session.receive(f.child, f.opened()))
        assertNotNull(f.begin())
        assertEquals("OpenFluxHttpSession(redacted)", f.session.toString())
    }
    @Test fun firstRequestIsExactInitialGetAndForeignOriginNeverQueued() {
        val f = Fixture(); f.ready()
        for (target in listOf("http://localhost:4321/path", "http://127.0.0.1:4322/path", "https://127.0.0.1:4321/path", f.address + "#fragment",
            "http://user@127.0.0.1:4321/path", "http://127.0.0.1:04321/path", f.address + "/resource"))
            assertNull(f.session.enqueue("GET", target, emptyList(), byteArrayOf()))
        assertNull(f.session.enqueue("POST", f.address, emptyList(), byteArrayOf()))
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf(1)))
        f.begin(); assertNull(f.session.nextCommand())
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf()))
    }
    @Test fun binaryResponseTransfersOnlyAfterEndAndHashAndIsOwnedWipeable() {
        val f = Fixture(); f.ready(); val id = f.begin(); val body = ByteArray(8193) { it.toByte() }
        f.session.receive(f.child, f.meta(id, body))
        assertNull(f.session.takeResponse())
        f.session.receive(f.child, f.chunk(id, 0, body.copyOfRange(0, 8192))); assertNull(f.session.takeResponse())
        f.session.receive(f.child, f.chunk(id, 8192, body.copyOfRange(8192, 8193))); assertNull(f.session.takeResponse())
        assertEquals(OpenFluxHttpSession.Received.COMPLETE, f.session.receive(f.child, f.end(id)))
        val response = f.session.takeResponse()!!
        assertArrayEquals(body, response.body); assertEquals(id, response.request); assertEquals(200, response.status)
        assertEquals("OpenFluxHttpResponse(redacted)", response.toString())
        assertNull(f.session.takeResponse()); f.session.close()
        assertArrayEquals(body, response.body) // Already transferred bytes are not revocable by session.
        response.close(); assertTrue(response.body.all { it == 0.toByte() })
    }
    @Test fun staleChildOrRequestCannotPoisonCurrentResponse() {
        val f = Fixture(); f.ready(); val id = f.begin()
        f.session.receive("e".repeat(32), "MCRF_OPENFLUX_HTTP_BROKEN".toByteArray())
        f.session.receive(f.child, f.meta("f".repeat(32), byteArrayOf(1)))
        f.session.receive(f.child, f.end("f".repeat(32)))
        f.complete(id, byteArrayOf(7)); assertArrayEquals(byteArrayOf(7), f.session.takeResponse()!!.body)
    }
    @Test fun malformedOutOfOrderOrHashMismatchIsTerminalWithOneScopedCancel() {
        for (kind in listOf("chunk", "offset", "digest", "duplicate")) {
            val f = Fixture(); f.ready(); val id = f.begin(); val body = byteArrayOf(1)
            if (kind != "chunk") f.session.receive(f.child, f.meta(id, body, if (kind == "digest") "c".repeat(64) else digest(body)))
            val result = when (kind) {
                "chunk" -> f.session.receive(f.child, f.chunk(id, 0, body))
                "offset" -> f.session.receive(f.child, f.chunk(id, 8192, body))
                "duplicate" -> f.session.receive(f.child, f.meta(id, body))
                else -> { f.session.receive(f.child, f.chunk(id, 0, body)); f.session.receive(f.child, f.end(id)) }
            }
            assertEquals(OpenFluxHttpSession.Received.REJECTED, result)
            assertEquals("OPENFLUX_HTTP_CLOSE|${f.ticket}|${f.permit}", f.session.takeCancelCommand())
            f.session.close(); assertNull(f.session.takeCancelCommand()); assertNull(f.session.nextCommand())
        }
    }
    @Test fun resourceQueueHasCountAndAggregateBoundsAndOneOutstandingCommand() {
        val f = Fixture(); f.initial()
        repeat(8) { assertNotNull(f.session.enqueue("GET", f.address + "/$it", emptyList(), byteArrayOf())) }
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf()))
        assertNotNull(f.session.nextCommand()); assertNull(f.session.nextCommand())
        val g = Fixture(); g.initial()
        assertNotNull(g.session.enqueue("POST", g.address, emptyList(), ByteArray(65536)))
        assertNull(g.session.enqueue("POST", g.address, emptyList(), ByteArray(65536)))
        assertNull(g.session.enqueue("POST", g.address, emptyList(), ByteArray(65537)))
        assertNull(g.session.enqueue("GET", g.address, listOf("Host" to "foreign"), byteArrayOf()))
    }
    @Test fun pauseExpiryAndChildLossRetireQueuedDataAndNeverResumeOldSession() {
        for (reason in listOf("pause", "deadline", "child")) {
            val f = Fixture(); f.initial(); f.session.enqueue("POST", f.address, emptyList(), byteArrayOf(1))
            when (reason) { "pause" -> { f.window.pause(); f.window.resume() }; "deadline" -> f.now = 1_000_000L; else -> f.alive = false }
            assertNull(f.session.nextCommand()); assertNotNull(f.session.takeCancelCommand())
            f.alive = true; f.now = 0; assertNull(f.session.openAfterUserAction(f.window.capture()))
        }
    }
    @Test fun lateOpenedAfterCloseOnlyProducesExactCancelAndNeverReopens() {
        val f = Fixture(); f.open(); f.session.close()
        assertNull(f.session.takeCancelCommand())
        assertEquals(OpenFluxHttpSession.Received.CONSUMED, f.session.receive(f.child, f.opened()))
        assertEquals("OPENFLUX_HTTP_CLOSE|${f.ticket}|${f.permit}", f.session.takeCancelCommand())
        f.session.receive(f.child, f.opened()); assertNull(f.session.takeCancelCommand())
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf()))
    }
    @Test fun commandCopiesCallerBodyAndNeverReusesCompletedRequestIdentity() {
        val f = Fixture(); f.initial(); val body = byteArrayOf(9)
        val id = f.session.enqueue("POST", f.address, emptyList(), body)!!; body[0] = 3
        val command = f.session.nextCommand()!!; assertTrue(command.length <= 131072)
        val parts = command.split('|'); assertEquals(id, parts[3])
        val payload = JSONArray(String(Base64.getDecoder().decode(parts[4]), Charsets.UTF_8))
        assertEquals("CQ==", payload.getString(3))
        f.complete(id, byteArrayOf()); f.session.takeResponse()!!.close()
        f.serial-- // A faulty nonce source must not admit an old response identity.
        assertNull(f.session.enqueue("GET", f.address, emptyList(), byteArrayOf()))
        val g = Fixture(); g.initial()
        repeat(58) {
            val next = g.session.enqueue("GET", g.address, emptyList(), byteArrayOf())!!
            assertNotNull(g.session.nextCommand()); g.complete(next, byteArrayOf()); g.session.takeResponse()!!.close()
        }
        assertNull(g.session.enqueue("GET", g.address, emptyList(), byteArrayOf()))
    }
    companion object {
        private fun frame(prefix: String, data: JSONObject) = (prefix + data).toByteArray(Charsets.UTF_8)
        private fun digest(body: ByteArray) = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
