package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection

class SubscriptionOptionsTest {
    private val a = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#A"
    @Test fun defaultsAndLegacyHoursRemainCompatible() {
        val options = SubscriptionOptions.from(JSONObject())
        assertEquals(360,options.intervalMinutes); assertTrue(options.clearOnSuccess); assertFalse(options.sendDeviceHeaders)
        assertEquals(SubscriptionOptions.DEFAULT_USER_AGENT,options.userAgent)
        assertEquals(options,SubscriptionOptions.from(options.json()))
    }
    @Test fun customIntervalAndManualRoundTrip() {
        listOf(0,30,31,90,360,Int.MAX_VALUE).forEach { value ->
            assertEquals(value,SubscriptionOptions.from(SubscriptionOptions(intervalMinutes=value).json()).intervalMinutes)
        }
        assertEquals("90m",SubscriptionSchedule.badgeMinutes(90))
        assertEquals("6h",SubscriptionSchedule.badgeMinutes(360))
        assertEquals("off",SubscriptionSchedule.badgeMinutes(0))
        assertEquals("25h",SubscriptionSchedule.badgeMinutes(1500))
    }
    @Test fun malformedBackupAndInjectionRejected() {
        listOf(JSONObject().put("intervalMinutes",29),JSONObject().put("intervalMinutes",-30),
            JSONObject().put("intervalMinutes",30.5),JSONObject().put("intervalMinutes",4294967326L),
            JSONObject().put("sendDeviceHeaders","false"),JSONObject().put("userAgent","test\r\nsecret: leak"),
            JSONObject().put("unknown",true)).forEach { value ->
            try { SubscriptionOptions.validate(value); fail("accepted $value") } catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun optOutIncludesNoDeviceIdentity() {
        assertEquals(mapOf("User-Agent" to "MCRF/0.1-port"),SubscriptionOptions().headers("fixture","Phone","16"))
    }
    @Test fun customAgentAndConsentedHeadersReachRequestsButNotRedirectHost() {
        val connections = mutableListOf<HttpURLConnection>()
        val opener = SubscriptionClient.Opener { uri ->
            object: HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = if(connections.size==1) 302 else 200
                override fun getHeaderField(name: String?) = if(name=="Location") "https://cdn.example.org/next" else null
                override fun getInputStream() = a.byteInputStream()
                override fun getContentLengthLong() = -1L
            }.also { connections.add(it) }
        }
        SubscriptionClient.fetch("https://example.com/sub",opener,"fixture-id",SubscriptionOptions(userAgent="Custom/1",sendDeviceHeaders=true),"Phone","16")
        assertEquals("Custom/1",connections[0].getRequestProperty("User-Agent"))
        assertEquals("fixture-id",connections[0].getRequestProperty("x-hwid"))
        assertEquals("Phone",connections[0].getRequestProperty("x-device-model"))
        assertEquals("Android",connections[0].getRequestProperty("x-device-os"))
        assertEquals("16",connections[0].getRequestProperty("x-ver-os"))
        assertNull(connections[1].getRequestProperty("x-hwid"))
        assertEquals("Custom/1",connections[1].getRequestProperty("User-Agent"))
    }
    @Test fun emptyOrFailedFetchNeverReachesClear() {
        val data = ConnectionCatalogue(""); data.replaceSubscription("https://example.com/sub",ServerImport.read(a)); val before = data.serialize()
        try { data.replaceSubscription("https://example.com/sub",emptyList(),JSONObject(),true); fail() } catch(_:IllegalArgumentException) { }
        assertEquals(before,data.serialize())
    }
    @Test fun malformedDownloadDoesNotCommitOrExposeAddress() {
        val data = ConnectionCatalogue(""); data.replaceSubscription("https://example.com/sub",ServerImport.read(a)); val before = data.serialize()
        val opener = SubscriptionClient.Opener { uri -> object: HttpURLConnection(uri.toURL()) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getInputStream() = "broken subscription".byteInputStream()
            override fun getContentLengthLong() = -1L
        } }
        try {
            val fetched = SubscriptionClient.fetch("https://example.com/sub?token=fixture-secret",opener)
            data.refreshFetched(mapOf("https://example.com/sub" to fetched),true); fail()
        } catch(error:IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("fixture-secret")) }
        assertEquals(before,data.serialize())
    }
}
