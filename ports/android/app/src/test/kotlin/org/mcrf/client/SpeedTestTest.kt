package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Fake transport/socket contracts only: never a live speed test, Android route, TLS or device acceptance. */
class SpeedTestTest {
    private class FixtureSocket(wire: String) : Socket() {
        private val input = ByteArrayInputStream(wire.toByteArray(Charsets.US_ASCII))
        val sent = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getOutputStream() = sent
        override fun setSoTimeout(timeout: Int) = Unit
    }
    private class Transport(private val act: (SpeedTestStage, Int, DiagnosticCancellation) -> SpeedTestSample) : SpeedTestTransport {
        val requests = mutableListOf<Pair<SpeedTestStage, Int>>()
        var closed = false
        override fun checkRoute() = Unit
        override fun exchange(stage: SpeedTestStage, bytes: Int, token: DiagnosticCancellation, progress: (Long) -> Unit): SpeedTestSample {
            requests.add(stage to bytes); progress(bytes.toLong()); return act(stage, bytes, token)
        }
        override fun close() { closed = true }
    }

    @Test fun decimalMbpsIsNotMiBPerSecondAndInvalidDurationsCannotProduceResults() {
        assertEquals(1.0, SpeedTestPlan.mbps(125_000, 1_000_000_000), 0.000001)
        assertThrows(IllegalArgumentException::class.java) { SpeedTestPlan.mbps(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SpeedTestPlan.mbps(0, 1) }
    }

    @Test fun oneRunHasExactlyThreeLatencyOneDownloadOneUploadAndMedianResponse() {
        var latency = 0
        val fixture = Transport { stage, bytes, _ ->
            SpeedTestSample(bytes.toLong(), if (stage == SpeedTestStage.LATENCY) listOf(3L, 1L, 2L)[latency++] * 1_000_000 else 1_000_000_000)
        }
        val result = SpeedTestRunner(fixture).run(DiagnosticCancellation(SpeedTestPlan.RUN_MS)) {}
        assertEquals(2.0, result.httpResponseMs, 0.001)
        assertEquals(67.108864, result.downloadMbps, 0.000001)
        assertEquals(16.777216, result.uploadMbps, 0.000001)
        assertEquals(listOf(SpeedTestStage.LATENCY to 0, SpeedTestStage.LATENCY to 0, SpeedTestStage.LATENCY to 0,
            SpeedTestStage.DOWNLOAD to 8 * 1024 * 1024, SpeedTestStage.UPLOAD to 2 * 1024 * 1024), fixture.requests)
        assertTrue(fixture.closed)
    }

    @Test fun failedOrShortTransferNeverRetriesUploadsOrPublishesAResult() {
        val failed = Transport { _, _, _ -> throw java.io.IOException("fixture") }
        assertThrows(java.io.IOException::class.java) { SpeedTestRunner(failed).run(DiagnosticCancellation(1000)) {} }
        assertEquals(1, failed.requests.size); assertTrue(failed.closed)
        val short = Transport { stage, bytes, _ -> SpeedTestSample(if (stage == SpeedTestStage.DOWNLOAD) bytes - 1L else bytes.toLong(), 1) }
        assertThrows(IllegalArgumentException::class.java) { SpeedTestRunner(short).run(DiagnosticCancellation(1000)) {} }
        assertEquals(4, short.requests.size); assertTrue(short.closed)
    }

    @Test fun cancellationClosesAttachedBlockingOperationWithoutStartingNextRequest() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val fixture = Transport { _, _, token ->
            val lease = token.attach(AutoCloseable { released.countDown() })
            try { entered.countDown(); check(released.await(2, TimeUnit.SECONDS)); token.check(); error("cancelled operation returned") }
            finally { lease.close() }
        }
        val token = DiagnosticCancellation(1000)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val done = worker.submit<Unit> { assertThrows(java.util.concurrent.CancellationException::class.java) { SpeedTestRunner(fixture).run(token) {} } }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); token.cancel()
            done.get(2, TimeUnit.SECONDS)
            assertEquals(1, fixture.requests.size); assertTrue(fixture.closed)
        } finally { token.cancel(); worker.shutdownNow() }
    }

    @Test fun watchdogDeadlineClosesABlockedWriteRatherThanTrustingReadTimeoutOnly() {
        val released = CountDownLatch(1)
        val fixture = Transport { _, _, child ->
            val lease = child.attach(AutoCloseable { released.countDown() })
            try { check(released.await(2, TimeUnit.SECONDS)); child.check(); error("deadline ignored") }
            finally { lease.close() }
        }
        assertThrows(SocketTimeoutException::class.java) { SpeedTestRunner(fixture).run(DiagnosticCancellation(30)) {} }
        assertTrue(fixture.closed); assertTrue(fixture.requests.size <= 1)
    }

    @Test fun routeChangeFailsClosedBeforeAnyPayloadOrFallback() {
        val fixture = object : SpeedTestTransport {
            var closed = false
            override fun checkRoute() { throw SpeedTestRouteChanged() }
            override fun exchange(stage: SpeedTestStage, bytes: Int, token: DiagnosticCancellation, progress: (Long) -> Unit): SpeedTestSample = error("must not send")
            override fun close() { closed = true }
        }
        assertThrows(SpeedTestRouteChanged::class.java) { SpeedTestRunner(fixture).run(DiagnosticCancellation(1000)) {} }
        assertTrue(fixture.closed)
    }

    private fun body(wire: String, maximum: Int, exact: Int?): Long {
        val socket = FixtureSocket(wire)
        val token = DiagnosticCancellation(1000)
        var received = 0L
        SpeedTestHttp.body(socket, DiagnosticsHttp.head(socket, token), maximum, exact, token) { received = it }
        return received
    }
    @Test fun fixedAndChunkedBodiesRequireCompleteExactBoundedData() {
        assertEquals(4L, body("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndata", 4, 4))
        assertEquals(4L, body("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nda\r\n2\r\nta\r\n0\r\n\r\n", 4, 4))
        assertThrows(DiagnosticsHttp.MalformedResponse::class.java) { body("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndat", 4, 4) }
        assertThrows(DiagnosticsHttp.MalformedResponse::class.java) { body("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\ndata\r\n", 4, 4) }
    }

    @Test fun oversizeCompressedAmbiguousOrUnknownFramingIsRejected() {
        for (headers in listOf("Content-Length: 5", "Content-Length: 3", "Content-Encoding: gzip\r\nContent-Length: 4",
                "Transfer-Encoding: chunked\r\nContent-Length: 4", "Transfer-Encoding: gzip", "X-Test: no-framing")) {
            assertThrows(DiagnosticsHttp.MalformedResponse::class.java) { body("HTTP/1.1 200 OK\r\n$headers\r\n\r\ndata!", 4, 4) }
        }
        assertThrows(DiagnosticsHttp.MalformedResponse::class.java) { body("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\ndata!\r\n0\r\n\r\n", 4, 4) }
    }

    @Test fun fixedOriginRequestHasNoCredentialsAndRedirectsAreNotFollowed() {
        val socket = FixtureSocket("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        SpeedTestHttp.exchange(socket, SpeedTestStage.LATENCY, 0, DiagnosticCancellation(1000)) {}
        val request = socket.sent.toString("US-ASCII")
        assertTrue(request.startsWith("GET /__down?bytes=0 HTTP/1.1\r\nHost: speed.cloudflare.com\r\n"))
        assertTrue(request.contains("Accept-Encoding: identity\r\n"))
        assertFalse(request.contains("Authorization")); assertFalse(request.contains("Cookie"))
        val redirect = FixtureSocket("HTTP/1.1 302 Found\r\nLocation: https://example.test\r\nContent-Length: 0\r\n\r\n")
        assertThrows(java.io.IOException::class.java) { SpeedTestHttp.exchange(redirect, SpeedTestStage.LATENCY, 0, DiagnosticCancellation(1000)) {} }
    }

    @Test fun uploadSendsExactlyTheBoundedSyntheticPayloadAndRequiresServerResponse() {
        val socket = FixtureSocket("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        val sample = SpeedTestHttp.exchange(socket, SpeedTestStage.UPLOAD, SpeedTestPlan.UPLOAD_BYTES, DiagnosticCancellation(5000)) {}
        val sent = socket.sent.toByteArray()
        val delimiter = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val headerEnd = (0..sent.size - 4).first { offset -> delimiter.indices.all { sent[offset + it] == delimiter[it] } } + 4
        val headers = sent.copyOfRange(0, headerEnd).toString(Charsets.US_ASCII)
        assertTrue(headers.startsWith("POST /__up HTTP/1.1\r\nHost: speed.cloudflare.com\r\n"))
        assertTrue(headers.contains("Content-Length: ${SpeedTestPlan.UPLOAD_BYTES}\r\n"))
        assertEquals(SpeedTestPlan.UPLOAD_BYTES, sent.size - headerEnd)
        assertEquals(SpeedTestPlan.UPLOAD_BYTES.toLong(), sample.bytes)
        val noAck = FixtureSocket("")
        assertThrows(DiagnosticsHttp.MalformedResponse::class.java) {
            SpeedTestHttp.exchange(noAck, SpeedTestStage.UPLOAD, SpeedTestPlan.UPLOAD_BYTES, DiagnosticCancellation(5000)) {}
        }
    }
}
