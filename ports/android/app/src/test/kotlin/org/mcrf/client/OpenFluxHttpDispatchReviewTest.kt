package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenFluxHttpDispatchReviewTest {
    private val child = "a".repeat(32); private val ticket = "b".repeat(32)
    private val registry = OpenFluxVerificationRegistry()
    private var current: OpenFluxVerificationRegistry.Request? = null
    private var observed: String? = null
    private var writes = 0
    private fun pending() = frame(OpenFluxHttpWire.PENDING, JSONObject().put("id", ticket).put("kind", "own_http"))
    private fun dispatch(bytes: ByteArray, enabled: Boolean = true, closed: Boolean = false, c: String = child) =
        OpenFluxHttpDispatch.receive(bytes, c, enabled, closed, registry, current?.id, observed, { true }, { writes++; true })
    private fun register(): OpenFluxVerificationRegistry.Request {
        val result = dispatch(pending()); assertEquals(OpenFluxHttpDispatch.State.PENDING, result.state)
        return result.request!!.also { current = it; observed = it.nativeId }
    }
    @Test fun disabledClosedAndMalformedPrivateFamilyNeverReachesNormalDiagnostics() {
        assertFalse(OpenFluxHttpVerificationPolicy.productionEnabled)
        assertEquals(OpenFluxHttpDispatch.State.NOT_HTTP, dispatch("ordinary".toByteArray()).state)
        assertEquals(OpenFluxHttpDispatch.State.REJECTED, dispatch(pending(), enabled = false).state)
        assertEquals(OpenFluxHttpDispatch.State.CONSUMED, dispatch("MCRF_OPENFLUX_HTTP_BROKEN".toByteArray(), closed = true).state)
        assertEquals(OpenFluxHttpDispatch.State.REJECTED, dispatch("MCRF_OPENFLUX_HTTP_BROKEN".toByteArray()).state)
        assertEquals(0, writes)
    }
    @Test fun pendingCreatesOnlySharedPoolHttpKindWithoutBrowserOrPageRequest() {
        val request = register(); assertTrue(request.http); assertFalse(request.inline)
        assertFalse(registry.browserOwnedBy(request.id)); assertEquals(0, writes)
        assertSame(request, dispatch(pending()).request)
        registry.cancel(request.id); assertEquals(OpenFluxHttpDispatch.State.STALE, dispatch(pending()).state)
        assertNull(registry.get(request.id))
    }
    @Test fun frameCannotBeReinterpretedAsCookieOrOfflineInlineTraffic() {
        current = registry.registerInline(child, OpenFluxDocumentWire.Pending(ticket), { true }, { true })
        observed = ticket
        assertEquals(OpenFluxHttpDispatch.State.REJECTED,
            dispatch(frame(OpenFluxHttpWire.END, JSONObject().put("id", ticket).put("request", "c".repeat(32)))).state)
        assertNotNull(registry.get(current!!.id))
    }
    @Test fun staleChildOrTicketCannotMutateCurrentHttpRequest() {
        val request = register()
        val end = frame(OpenFluxHttpWire.END, JSONObject().put("id", ticket).put("request", "c".repeat(32)))
        assertEquals(OpenFluxHttpDispatch.State.REJECTED, dispatch(end, c = "e".repeat(32)).state)
        assertEquals(OpenFluxHttpDispatch.State.STALE, dispatch(frame(OpenFluxHttpWire.END,
            JSONObject().put("id", "f".repeat(32)).put("request", "c".repeat(32)))).state)
        assertSame(request, registry.get(request.id)); assertEquals(0, writes)
    }
    @Test fun scopedSourceDispatchFeedsOnlyManuallyArmedSession() {
        val request = register(); assertTrue(registry.claimBrowser(request.id))
        val window = OpenFluxWindowAdmission(); window.resume()
        val open = request.openHttpAfterUserAction(window, window.capture())!!.toString(Charsets.US_ASCII).trim().substringAfterLast('|')
        assertEquals(OpenFluxHttpDispatch.State.CONSUMED, dispatch(frame(OpenFluxHttpWire.OPENED,
            JSONObject().put("id", ticket).put("request", open).put("permit", "d".repeat(32))
                .put("address", "http://127.0.0.1:4321/path"))).state)
        val id = request.enqueueHttp("GET", "http://127.0.0.1:4321/path", emptyList(), byteArrayOf())!!
        request.nextHttpCommand()
        dispatch(frame(OpenFluxHttpWire.META, JSONObject().put("id", ticket).put("request", id).put("status", 200)
            .put("headers", JSONArray()).put("bytes", 0).put("sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")))
        assertEquals(OpenFluxHttpDispatch.State.CONSUMED, dispatch(frame(OpenFluxHttpWire.END,
            JSONObject().put("id", ticket).put("request", id))).state)
        request.takeHttpResponse()!!.close(); assertEquals(0, writes) // Explicit commands still not offered to a child.
    }
    companion object { private fun frame(prefix: String, data: JSONObject) = (prefix + data).toByteArray(Charsets.UTF_8) }
}
