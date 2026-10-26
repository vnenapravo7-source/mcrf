package org.mcrf.client

import java.util.Base64
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Queue/codec models only; no JS/port/WebView/native/HTTP execution. */
class OpenFluxHttpPageChannelReviewTest {
    private class Fixture {
        val window = OpenFluxWindowAdmission().apply { resume() }
        var alive = true
        val policy = OpenFluxHttpPagePolicy("http://127.0.0.1:4321/check", "a".repeat(32))
        val channel = OpenFluxHttpPageChannel(policy, window, window.capture()!!, { alive })
        fun frame(index: Int, method: String = "POST", body: ByteArray = byteArrayOf(), url: String = policy.initialUrl,
                  headers: JSONArray = JSONArray()): String = JSONArray().put("request").put("%032x".format(index))
            .put(method).put(url).put(headers).put(Base64.getEncoder().encodeToString(body)).toString()
    }
    @Test fun postBinaryBodyAndHeadersAreExplicitAndExactNativeTargetIsCaptured() {
        val f = Fixture(); val raw = byteArrayOf(0, -1, 17, 3)
        assertTrue(f.channel.accept(f.frame(1, body = raw, headers = JSONArray().put(JSONArray().put("X-Test").put("yes")))))
        val request = f.channel.next()!!
        assertEquals("POST", request.method); assertEquals("http://127.0.0.1:4321/check", request.target)
        assertArrayEquals(raw, request.body); assertEquals(listOf("X-Test" to "yes"), request.headers)
        assertTrue(f.channel.current(request)); assertNull(f.channel.next()); assertTrue(f.channel.complete(request))
        assertTrue(request.body.all { it == 0.toByte() }); assertFalse(f.channel.complete(request)); assertTrue(f.channel.empty())
    }
    @Test fun pageMessagesCannotOpenSendForegroundOrUseExternalTarget() {
        val f = Fixture()
        for (kind in listOf("open", "reply", "send", "foreground", "cancel"))
            assertFalse(f.channel.accept(JSONArray(f.frame(1)).put(0, kind).toString()))
        for (url in listOf("http://127.0.0.1:4321/check", "https://example.com/", f.policy.origin + ":443/check"))
            assertFalse(f.channel.accept(f.frame(1, url = url)))
        assertTrue(f.channel.empty())
    }
    @Test fun canonicalBase64BodyMethodAndHeaderShapesAreBounded() {
        val f = Fixture()
        for (encoded in listOf("YQ", "YR==", "!!!!")) assertFalse(f.channel.accept(JSONArray(f.frame(1)).put(5, encoded).toString()))
        assertFalse(f.channel.accept(f.frame(1, body = ByteArray(65537))))
        assertFalse(f.channel.accept(f.frame(1, "GET", byteArrayOf(1))))
        assertFalse(f.channel.accept(f.frame(1, "HEAD", byteArrayOf(1))))
        assertFalse(f.channel.accept(f.frame(1, headers = JSONArray().put(JSONArray().put("Connection").put("close")))))
        assertFalse(f.channel.accept(f.frame(1) + " trailing")); assertFalse(f.channel.accept("[]"))
        assertTrue(f.channel.accept(f.frame(1, body = ByteArray(65536))))
    }
    @Test fun queueIncludesActiveSlotAndAggregateBodyBudgetDoesNotGrowUnbounded() {
        val f = Fixture()
        repeat(8) { assertTrue(f.channel.accept(f.frame(it + 1))) }
        val active = f.channel.next()!!; assertFalse(f.channel.accept(f.frame(9)))
        assertTrue(f.channel.complete(active)); assertTrue(f.channel.accept(f.frame(9)))
        val g = Fixture()
        assertTrue(g.channel.accept(g.frame(1, body = ByteArray(65536))))
        assertFalse(g.channel.accept(g.frame(2, body = ByteArray(65536)))) // URLs/headers also reserve bytes.
    }
    @Test fun completedIdsAreTombstonedAndLifetimeAttemptsBounded() {
        val f = Fixture()
        repeat(OpenFluxHttpPageChannel.MAX_REQUESTS) { index ->
            assertTrue(f.channel.accept(f.frame(index + 1))); assertTrue(f.channel.complete(f.channel.next()!!))
        }
        assertFalse(f.channel.accept(f.frame(1))); assertFalse(f.channel.accept(f.frame(59)))
    }
    @Test fun closeWipesActiveAndQueuedOwnershipWithoutClaimingCopiesRevoked() {
        val f = Fixture(); val g = Fixture()
        assertTrue(f.channel.accept(f.frame(1, body = byteArrayOf(1))))
        val active = f.channel.next()!!
        assertTrue(g.channel.accept(g.frame(1))); val foreign = g.channel.next()!!
        assertFalse(f.channel.complete(foreign)); assertTrue(f.channel.current(active))
        assertTrue(f.channel.accept(f.frame(2, body = byteArrayOf(2))))
        f.channel.close(); assertTrue(active.body.all { it == 0.toByte() })
        assertFalse(f.channel.current(active)); assertNull(f.channel.next()); assertFalse(f.channel.accept(f.frame(3)))
        g.channel.close()
    }
    @Test fun pauseResumeAndOwnerLossCannotRevivePageQueue() {
        val f = Fixture(); assertTrue(f.channel.accept(f.frame(1, body = byteArrayOf(1))))
        val active = f.channel.next()!!; f.window.pause(); f.window.resume()
        assertFalse(f.channel.current(active)); assertTrue(active.body.all { it == 0.toByte() })
        assertFalse(f.channel.accept(f.frame(2)))
        val g = Fixture(); g.alive = false; assertFalse(g.channel.accept(g.frame(1))); g.alive = true
        assertFalse(g.channel.accept(g.frame(2)))
    }
    @Test fun interceptorNeverGainsPostButSeparateExplicitBodyChannelCanAdmitMethods() {
        val f = Fixture()
        assertNull(f.policy.interceptTarget(f.policy.initialUrl, "POST"))
        for ((index, method) in listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").withIndex()) {
            assertTrue(f.channel.accept(f.frame(index + 1, method)))
            val request = f.channel.next()!!; assertTrue(f.channel.complete(request))
        }
        assertEquals("OpenFluxHttpPageChannel(redacted)", f.channel.toString())
    }
}
