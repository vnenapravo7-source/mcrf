package org.mcrf.client

import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class InviteSnapshotSyncTest {
    private val manifest=InviteCodeSync.Manifest(null,"https://unavailable.invalid/sub","unused")
    private fun plan()=ImportPlan.prepare("socks://127.0.0.1:1080"){error("Unexpected fetch")}
    @Test fun anySubscriptionFailureAbortsTheWholeSnapshot() {
        var fetchedBypass=false
        try {InviteCodeSync.prepareAvailable(manifest,{throw IllegalArgumentException("fetch",UnknownHostException())},{fetchedBypass=true;plan()});fail()}
        catch(error:IllegalArgumentException){assertTrue(error.cause is UnknownHostException)}
        assertFalse(fetchedBypass)
    }
    @Test fun workingSubscriptionAndBypassHaveNoWarning() {
        val result=InviteCodeSync.prepareAvailable(manifest,{plan()},::plan)
        assertTrue(result.entries.isNotEmpty())
    }
    @Test fun cancellationNeverContinuesToBypass() {
        var called=false
        try {InviteCodeSync.prepareAvailable(manifest,{throw InterruptedException()},{called=true;plan()});fail()}
        catch(_:InterruptedException){}
        assertFalse(called)
    }
    @Test fun brokenBypassStillAbortsBeforeAnyCommit() {
        try {InviteCodeSync.prepareAvailable(manifest,{plan()},{throw IllegalStateException("bypass unavailable")});fail()}
        catch(e:IllegalStateException){assertEquals("bypass unavailable",e.message)}
    }
    @Test fun standaloneServersAreIncludedAlongsideBypassAndCountAsVpnAvailable() {
        val direct="socks://127.0.0.1:1081"
        val current=InviteCodeSync.Manifest(null,null,"unused",listOf(direct))
        val result=InviteCodeSync.prepareAvailable(current,{error("No subscription")},::plan)
        assertTrue(current.vpnAvailable);assertEquals(2,result.entries.size)
        assertEquals(listOf(direct),InviteCodeSync.directLinks(org.json.JSONObject().put("serverLinks",org.json.JSONArray(listOf(direct)))))
    }
    @Test fun standaloneFieldCannotHideNetworkSubscriptions() {
        try {InviteCodeSync.directLinks(org.json.JSONObject().put("serverLinks",org.json.JSONArray(listOf("https://example.invalid/private"))));fail()}
        catch(_:IllegalArgumentException){}
    }
    @Test fun bypassOnlySchemaOneWithEmptyOrOmittedVpnFieldsImportsOnlyBypass() {
        val fixtureUrl="https://179.254.154.28/wd/sub/fixture-only-0000#"+java.util.Base64.getUrlEncoder()
            .withoutPadding().encodeToString(ByteArray(32))
        val snapshots=listOf(
            """{"schema":1,"subscriptionUrl":"","serverLinks":[],"bypassUrl":"$fixtureUrl"}""",
            """{"schema":1,"bypassUrl":"$fixtureUrl"}"""
        )
        for (snapshot in snapshots) {
            val manifest=InviteCodeSync.parseManifest(org.json.JSONObject(snapshot))
            assertFalse(manifest.vpnAvailable)
            assertNull(manifest.subscription)
            assertTrue(manifest.serverLinks.isEmpty())
            assertEquals(fixtureUrl,manifest.bypass)
            var bypassFetches=0
            val imported=InviteCodeSync.prepareAvailable(manifest,{error("No VPN subscription")}) {
                bypassFetches++
                plan()
            }
            assertEquals(1,bypassFetches)
            assertEquals(1,imported.entries.size)
        }
    }
    @Test fun explicitlyEmptyPageIsAValidEmptySnapshot() {
        val result=InviteCodeSync.prepareAvailable(InviteCodeSync.Manifest(null,null,null),{error("No fetch")},{error("No bypass")})
        assertTrue(result.entries.isEmpty())
    }
}
