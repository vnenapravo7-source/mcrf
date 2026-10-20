package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CancellationException

class WarpOperationsTest {
    @Test fun nativeAutoAwgCandidatesRejectedByAppMustFallBackToWg() {
        val protocols=mutableListOf<String>()
        val awg=candidate().put("protocol","awg").put("config",WarpProfile.initial(account()).original.replace("[Interface]","[Interface]\nH1 = 123"))
        val found=WarpOperations.search(account(),{request ->
            val protocol=request.getString("protocol");protocols.add(protocol)
            JSONObject().put("candidates",JSONArray().put(if(protocol=="auto")awg else candidate()))
        },{profile ->if(profile.contains("H1"))throw IllegalArgumentException("Fixture AWG not accepted");"warp=on\nloc=NL\ncolo=AMS"})
        assertEquals(listOf("auto","wg"),protocols);assertEquals("NL",found.trace.country)
    }
    @Test fun scannerInternalDeadlineDoesNotPretendUserCancelled() {
        var scans=0
        val e=assertThrows(WarpOperations.SearchExhausted::class.java) {
            WarpOperations.search(account(),{scans++;JSONObject().put("error","cancelled")},{error("no candidates")})
        }
        assertEquals(3,scans);assertTrue(e.detail.contains("время поиска"))
    }
    @Test fun diagnosticsUseOnlyFixedReasonsNotCandidateSecrets() {
        val e=assertThrows(WarpOperations.SearchExhausted::class.java) {
            WarpOperations.search(account(),{JSONObject().put("candidates",JSONArray().put(candidate()))},{throw IllegalStateException("secret account fixture")})
        }
        assertTrue(e.detail.contains("туннеля"));assertFalse(e.detail.contains("secret"));assertFalse(e.detail.contains(key))
    }
    @Test fun retriesExpandPortsRatherThanRepeatingQuickSearch() {
        val extended=mutableListOf<Boolean>()
        assertThrows(WarpOperations.SearchExhausted::class.java) {
            WarpOperations.search(account(),{extended.add(it.getBoolean("extended"));JSONObject().put("error","no_endpoints")},{error("no candidates")})
        }
        assertEquals(listOf(false,true,true),extended)
    }
    @Test fun traceTimeoutDoesNotCancelRemainingAttempts() {
        var scans=0
        assertThrows(WarpOperations.SearchExhausted::class.java) {
            WarpOperations.search(account(),{scans++;JSONObject().put("candidates",JSONArray().put(candidate()))},{WarpProbeDeadline.check(false,true);""})
        }
        assertEquals(3,scans)
    }
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it+1).toByte() })
    private fun account() = JSONObject().put("id", "local-fixture").put("token", "local-fixture-only").put("private_key", key).put("peer_public_key", WarpProfile.PEER_KEY).put("ipv4", "172.16.0.2").toString()
    private fun candidate() = JSONObject().put("protocol", "wg").put("config", WarpProfile.initial(account()).original)
    @Test fun registerValidatesRealBackendResult() {
        var called = false
        val result = WarpOperations.register({ request -> called=true; assertEquals("register", request.getString("operation")); JSONObject().put("account", account()) })
        assertTrue(called); assertEquals("local-fixture", JSONObject(result).getString("id"))
        assertThrows(Exception::class.java) { WarpOperations.register({ JSONObject().put("account", JSONObject(account()).put("token", "").toString()) }) }
    }
    @Test fun wrongCountryRetriesNextCandidateBeforeCommit() {
        var calls = 0
        val result = WarpOperations.scan(account(), { JSONObject().put("candidates", JSONArray().put(candidate()).put(candidate())) }, { calls++; if(calls == 1) "warp=on\nloc=RU\ncolo=DME" else "warp=on\nloc=NL\ncolo=AMS" })
        assertEquals(2, calls); assertEquals("NL", result.trace.country); assertTrue(result.server.name.contains("AMS")); assertFalse(result.toString().contains(key))
    }
    @Test fun directOrBadTraceNeverReturnsCandidate() {
        assertThrows(Exception::class.java) { WarpOperations.scan(account(), { JSONObject().put("candidates", JSONArray().put(candidate())) }, { "warp=off\nloc=NL\ncolo=AMS" }) }
    }
    @Test fun scannerCannotChangeAccountKeyOrProtocol() {
        var called = false
        val changed = candidate().put("config", WarpProfile.initial(account()).original.replace(key, Base64.getEncoder().encodeToString(ByteArray(32) { 3 })))
        assertThrows(Exception::class.java) { WarpOperations.scan(account(), { JSONObject().put("candidates", JSONArray().put(changed)) }, { called=true; "warp=on\nloc=NL\ncolo=AMS" }) }
        assertFalse(called)
        assertThrows(Exception::class.java) { WarpOperations.scan(account(), { JSONObject().put("candidates", JSONArray().put(candidate().put("protocol", "awg"))) }, { "warp=on\nloc=NL\ncolo=AMS" }) }
    }
    @Test fun cancellationPropagatesAndNoSecretsInBackendErrors() {
        assertThrows(CancellationException::class.java) { WarpOperations.scan(account(), { error("not called") }, { error("not called") }, cancelled={ true }) }
        assertThrows(CancellationException::class.java) { WarpOperations.scan(account(), { JSONObject().put("candidates", JSONArray().put(candidate())) }, { throw CancellationException() }) }
        val error = assertThrows(Exception::class.java) { WarpOperations.register({ JSONObject().put("error", key) }) }
        assertFalse(error.message.orEmpty().contains(key))
        assertThrows(CancellationException::class.java) { WarpOperations.register({ JSONObject().put("error","cancelled") }) }
    }
    @Test fun threeCompleteForeignAttemptsNeverRegisterOrFallbackToRussia() {
        val progress=mutableListOf<Int>();var scans=0;var traces=0
        val error=assertThrows(WarpOperations.SearchExhausted::class.java) {
            WarpOperations.search(account(), { request ->
                assertEquals("scan",request.getString("operation"));assertEquals("foreign",request.getString("country"));scans++
                JSONObject().put("candidates",JSONArray().put(candidate()))
            }, { traces++;"warp=on\nloc=RU\ncolo=DME" },progress={current,total -> assertEquals(3,total);progress.add(current)})
        }
        assertTrue(error.foreign);assertEquals(listOf(1,2,3),progress);assertEquals(3,scans);assertEquals(3,traces)
    }
    @Test fun successfulSecondAttemptStopsAndRetainsSameRegisteredAccount() {
        var scans=0;var registrations=0
        val backend=WarpOperations.Backend { request ->
            if(request.getString("operation")=="register") {registrations++;JSONObject().put("account",account())}
            else {scans++;assertEquals(key,JSONObject(request.getString("account")).getString("private_key"));JSONObject().put("candidates",JSONArray().put(candidate()))}
        }
        val registered=WarpOperations.register(backend)
        var traces=0
        val result=WarpOperations.search(registered,backend,{ traces++;if(traces==1) "warp=off\nloc=NL\ncolo=AMS" else "warp=on\nloc=NL\ncolo=AMS" })
        assertEquals("NL",result.trace.country);assertEquals(2,scans);assertEquals(1,registrations)
    }
    @Test fun retryingWholeSearchReusesAccountWithoutRegistration() {
        var registrations=0;var scans=0
        val backend=WarpOperations.Backend { request ->
            if(request.getString("operation")=="register") {registrations++;JSONObject().put("account",account())}
            else {scans++;JSONObject().put("error","no_endpoints")}
        }
        val registered=WarpOperations.register(backend)
        repeat(2) { assertThrows(WarpOperations.SearchExhausted::class.java) {WarpOperations.search(registered,backend,{error("no candidates")})} }
        assertEquals(1,registrations);assertEquals(6,scans)
    }
    @Test fun cancelBetweenAttemptsPreventsFurtherScan() {
        var cancelled=false;var scans=0
        assertThrows(CancellationException::class.java) {
            WarpOperations.search(account(),{ scans++;cancelled=true;JSONObject().put("error","no_endpoints") },{error("no trace")},cancelled={cancelled})
        }
        assertEquals(1,scans)
    }
    @Test fun cancellationInsideTraceIsNotRetried() {
        var scans=0
        assertThrows(CancellationException::class.java) {WarpOperations.search(account(),{scans++;JSONObject().put("candidates",JSONArray().put(candidate()))},{throw CancellationException()})}
        assertEquals(1,scans)
    }
    @Test fun cancelledProgressNeverStartsAttempt() {
        var cancelled=false
        assertThrows(CancellationException::class.java) {WarpOperations.search(account(),{error("must not scan")},{error("must not trace")},cancelled={cancelled},progress={_,_->cancelled=true})}
    }
    @Test fun interruptedTraceCancelsInsteadOfTryingAnotherCandidateOrAttempt() {
        var scans=0
        try {
            assertThrows(CancellationException::class.java) {WarpOperations.search(account(),{scans++;JSONObject().put("candidates",JSONArray().put(candidate()).put(candidate()))},{throw InterruptedException()})}
            assertEquals(1,scans);assertTrue(Thread.currentThread().isInterrupted)
        } finally {Thread.interrupted()}
    }
    @Test fun explicitRussiaOptionsPermitRussiaWithoutAnotherRegistration() {
        var scans=0
        val result=WarpOperations.search(account(),{request ->
            scans++;assertEquals("scan",request.getString("operation"));assertEquals("RU",request.getString("country"))
            assertTrue(request.getBoolean("excludeDme"));JSONObject().put("candidates",JSONArray().put(candidate()))
        },{"warp=on\nloc=RU\ncolo=LED"},WarpOperations.Options(country="RU"))
        assertEquals(1,scans);assertEquals("RU",result.trace.country)
    }
    @Test fun noCountryOrLowLevelRequestCanOptIntoDme() {
        for(country in listOf("RU","foreign","any","NL")) {
            assertThrows(IllegalArgumentException::class.java){WarpOperations.Options(country=country,excludeDme=false)}
            assertThrows(IllegalArgumentException::class.java){WarpProfile.request("register","","auto",country,false,false)}
            assertThrows(IllegalArgumentException::class.java){WarpProfile.trace("warp=on\nloc=RU\ncolo=DME",country,false)}
        }
    }
}
