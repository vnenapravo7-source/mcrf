package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mcrf.steerprep.SteerMcrfSnapshotAdapter
import java.util.UUID

class ClientSteerFixtureTest {
    private fun health() = "http://198.18.0.2/owned/" + UUID.randomUUID().toString().replace("-", "")
    private fun original(host: String = "192.168.1.20", port: String = "32123") =
        "vless://${UUID.randomUUID()}@$host:$port?security=none&type=tcp&encryption=none"
    private fun source(host: String = "192.168.1.20", port: String = "32123") = JSONObject().put(
        "mcrf_steer_fixture", JSONObject().put("original", original(host, port))
            .put("dialIpv4", host).put("snapshot", JSONObject(PINNED_SNAPSHOT))
    )
    private fun fixture(source: JSONObject) = source.getJSONObject("mcrf_steer_fixture")
    private fun reject(source: JSONObject, url: String = health()) {
        val error = assertThrows(IllegalArgumentException::class.java) { ClientSteerFixture.parse(source, url) }
        assertTrue(error.message.orEmpty().startsWith("STEER_FIXTURE_"))
        assertFalse(error.message.orEmpty().contains("vless://"))
    }
    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    @Test fun exactPublicSnapshotIsPinnedIndependentlyOfProductionConstant() {
        assertEquals(canonical(JSONObject(PINNED_SNAPSHOT)), canonical(JSONObject(ClientSteerFixture.SYNTHETIC_SNAPSHOT_JSON)))
    }
    @Test fun encryptedFixturesPinTrustHostnameAndNoArbitraryPolicyExpansion(){
        for(protocol in listOf("DoT","DoH")){
            val snapshot=ClientSteerFixture.p1TlsSnapshot(protocol);ClientSteerFixture.requireP1TlsSnapshot(snapshot,protocol)
            val input=source().also {fixture(it).put("snapshot",snapshot)}
            val parsed=ClientSteerFixture.parse(input,health());val state=JSONObject(parsed.settingsJson)
            assertEquals("Cloudflare",state.getJSONObject("globalDns").getString("provider"));assertEquals(protocol,state.getJSONObject("globalDns").getString("protocol"))
            assertEquals("Google",state.getJSONArray("rules").getJSONObject(1).getJSONObject("dns").getString("provider"))
            assertEquals(4,state.getJSONArray("rules").length());assertEquals("block",state.getString("defaultTarget"))
            snapshot.getJSONObject("routing").getJSONObject("globalDns").put("provider","Google");reject(input)
        }
        assertThrows(IllegalArgumentException::class.java){ClientSteerFixture.p1TlsSnapshot("UDP")}
    }

    @Test fun p1ModelIsExactDetachedAndUsesRealAdapterWithoutLegacyDowngrade() {
        val snapshot=ClientSteerFixture.p1Snapshot();val before=snapshot.toString()
        ClientSteerFixture.requireP1Snapshot(snapshot)
        val source=source().also {fixture(it).put("snapshot",snapshot)}
        val result=ClientSteerFixture.parse(source,health());val projected=JSONObject(result.settingsJson)
        assertEquals(2,projected.getInt("steerVersion"));assertTrue(projected.getBoolean("ipv6"))
        assertEquals("block",projected.getString("defaultTarget"));assertEquals(3,projected.getJSONArray("rules").length())
        assertEquals("198.18.0.253",projected.getJSONArray("rules").getJSONObject(1).getJSONObject("dns").getString("custom"))
        assertEquals(before,snapshot.toString())
        snapshot.getJSONObject("routing").put("defaultTarget","direct")
        assertEquals("block",JSONObject(result.snapshotJson).getJSONObject("routing").getString("defaultTarget"))
        assertEquals("block",ClientSteerFixture.p1Snapshot().getJSONObject("routing").getString("defaultTarget"))
    }

    @Test fun p1ModelCannotAdmitArbitraryDnsTargetsRulesOrInferredCapabilities() {
        val mutations=listOf<(JSONObject)->Unit>(
            {it.getJSONObject("routing").put("defaultTarget","direct")},
            {it.getJSONObject("routing").getJSONObject("globalDns").put("custom","1.1.1.1")},
            {it.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).put("text","IP-CIDR,0.0.0.0/0")},
            {it.getJSONObject("tun").put("mixedEnabled",true)},
            {it.put("ipv4Compatibility",true)}, {it.put("mcrf_snapshot_model",2)}
        )
        mutations.forEach {change->val source=source();val snapshot=ClientSteerFixture.p1Snapshot();change(snapshot);fixture(source).put("snapshot",snapshot);reject(source)}
        assertThrows(IllegalArgumentException::class.java){ClientSteerFixture.requireP1Snapshot(JSONObject(PINNED_SNAPSHOT))}
    }
    @Test fun p1ProxyFixtureIsNonceBoundAndHasDisjointOriginalPackageTargets(){
        val url=health();val nonce=url.substringAfterLast('/');val snapshot=ClientSteerFixture.p1ProxySnapshot(nonce)
        val source=source().also {fixture(it).put("snapshot",snapshot)}
        val prepared=ClientSteerFixture.parse(source,url);val settings=JSONObject(prepared.settingsJson)
        assertEquals(nonce,settings.getJSONObject("localProxy").getString("password"));assertEquals(0,settings.getJSONObject("localProxy").getInt("port"))
        val rules=settings.getJSONArray("rules")
        assertEquals("org.mcrf.client",rules.getJSONObject(0).getJSONObject("nativeMatch").getJSONArray("package_name").getString(0))
        assertEquals("org.mcrf.p1owner.peer",rules.getJSONObject(1).getJSONObject("nativeMatch").getJSONArray("package_name").getString(0))
        assertNotEquals(rules.getJSONObject(0).getJSONObject("nativeMatch").getJSONArray("ip_cidr").toString(),rules.getJSONObject(1).getJSONObject("nativeMatch").getJSONArray("ip_cidr").toString())
        reject(source,health())
        snapshot.getJSONObject("routing").getJSONArray("rules").getJSONObject(1).getJSONObject("nativeMatch").put("package_name",JSONArray().put("org.mcrf.client"))
        reject(source,url)
    }

    @Test fun validCaptureUsesRealCanonicalAdapterAndDoesNotModifyInput() {
        val source = source()
        val before = source.toString()
        val result = ClientSteerFixture.parse(source, health())
        assertEquals(fixture(source).getString("original"), result.original)
        assertEquals("192.168.1.20", result.dialIpv4)
        assertEquals(canonical(JSONObject(PINNED_SNAPSHOT)), canonical(JSONObject(result.snapshotJson)))
        val converted = SteerMcrfSnapshotAdapter.convert(result.snapshotJson)
        assertTrue(converted.supported)
        assertTrue(converted.unsupported.isEmpty())
        assertEquals(converted.settingsJson, result.settingsJson)
        assertEquals(before, source.toString())
        val settings = JSONObject(result.settingsJson)
        assertEquals(setOf("version", "defaultTarget", "globalDns", "excludedApps", "vpnApps", "rules"),
            settings.keys().asSequence().toSet())
        assertEquals("block", settings.getString("defaultTarget"))
        assertEquals("198.18.0.254", settings.getJSONObject("globalDns").getString("custom"))
        assertEquals(1, settings.getJSONArray("rules").length())
        assertEquals("IP-CIDR,198.18.0.2/32", settings.getJSONArray("rules").getJSONObject(0).getString("text"))
    }

    @Test fun resultOwnsOnlyImmutableStringsAndDoesNotPrintPrivateOriginal() {
        val source = source()
        val result = ClientSteerFixture.parse(source, health())
        val captured = listOf(result.original, result.settingsJson, result.dialIpv4, result.snapshotJson)
        fixture(source).put("original", "changed").put("dialIpv4", "8.8.8.8")
            .getJSONObject("snapshot").getJSONObject("routing").put("defaultTarget", "direct")
        JSONObject(result.settingsJson).put("defaultTarget", "direct")
        JSONObject(result.snapshotJson).put("schema", 2)
        assertEquals(captured, listOf(result.original, result.settingsJson, result.dialIpv4, result.snapshotJson))
        assertFalse(result.toString().contains(result.original))
        assertFalse(result.toString().contains(result.snapshotJson))
    }

    private fun reverseKeys(value: Any): Any = when (value) {
        is JSONObject -> JSONObject().apply {
            value.keys().asSequence().toList().reversed().forEach { put(it, reverseKeys(value.get(it))) }
        }
        is JSONArray -> JSONArray().apply { (0 until value.length()).forEach { put(reverseKeys(value.get(it))) } }
        else -> value
    }

    @Test fun objectKeyOrderDoesNotAffectAdmissionOrProjection() {
        val source = source()
        val first = ClientSteerFixture.parse(source, health())
        val second = ClientSteerFixture.parse(reverseKeys(source) as JSONObject, health())
        assertEquals(first.original, second.original)
        assertEquals(canonical(JSONObject(first.snapshotJson)), canonical(JSONObject(second.snapshotJson)))
        assertEquals(canonical(JSONObject(first.settingsJson)), canonical(JSONObject(second.settingsJson)))
    }

    @Test fun exactlyThreeQueryKeysMayAppearInAnyOrder() {
        val pairs = listOf("security=none", "type=tcp", "encryption=none")
        for (first in pairs) for (second in pairs - first) {
            val third = (pairs - first - second).single()
            val source = source()
            val uri = fixture(source).getString("original").substringBefore('?') + "?" + listOf(first, second, third).joinToString("&")
            fixture(source).put("original", uri)
            assertEquals(uri, ClientSteerFixture.parse(source, health()).original)
        }
    }

    @Test fun rfc1918BoundariesAndPortBoundariesAreAcceptedWithoutResolution() {
        for (ip in listOf("10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255")) {
            for (port in listOf("1", "80", "65535")) {
                assertEquals(ip, ClientSteerFixture.parse(source(ip, port), health()).dialIpv4)
            }
        }
    }

    @Test fun allNonPrivateOrNonCanonicalEndpointFormsAreRejected() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "127.0.0.1", "0.0.0.0", "169.254.1.1", "100.64.0.1",
            "198.18.0.2", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "11.0.0.1",
            "224.0.0.1", "255.255.255.255", "192.168.1.256", "192.168.01.2", "010.0.0.1",
            "10.1.2", "10.1.2.3.4", "10.1.2.-1", "10.1.2.+1", "10.1.2.3 ", " 10.1.2.3",
            "localhost", "example.invalid", "[::1]", "[fd00::1]", "0xc0a80101", "3232235777", "")) reject(source(ip))
    }

    @Test fun badPortsAreRejected() {
        for (port in listOf("0", "65536", "99999", "999999999999", "-1", "+80", "080", "80.0", "", "http")) reject(source(port = port))
    }

    @Test fun dialMustBeAStringAndExactlyTheCanonicalOriginalHost() {
        for (dial in listOf<Any>("10.0.0.1", "192.168.1.21", "8.8.8.8", true, 123, JSONObject.NULL, JSONObject(), JSONArray())) {
            reject(source().apply { fixture(this).put("dialIpv4", dial) })
        }
    }

    @Test fun uuidMustBeCanonicalLowercaseVersionFourWithRfcVariant() {
        val valid = UUID.randomUUID().toString()
        for (uuid in listOf("", "A" + valid.drop(1), valid.replaceRange(14, 15, "1"), valid.replaceRange(19, 20, "7"),
            valid.replace("-", ""), valid + ":password", "%" + valid, "{" + valid + "}", " " + valid)) {
            val source = source()
            fixture(source).put("original", original().replace(Regex("(?<=vless://)[^@]+"), uuid))
            reject(source)
        }
    }

    @Test fun uriRejectsEscapesPathsFragmentsPasswordsAndAnyQueryChange() {
        val base = original()
        val invalid = listOf(base + "#", base + "#label", base + "/", base + "\n", " " + base,
            base.replace("vless://", "VLESS://"), base.replace("vless://", "http://"),
            base.replace("@", ":password@"), base.replace("@", "%40"), base.replace(":32123?", ":32123/?"),
            base.replace("192.168.1.20", "192.168.1.%32%30"),
            base.replace("security=none", "security=tls"), base.replace("type=tcp", "type=ws"),
            base.replace("encryption=none", "encryption=auto"), base.replace("security=none", "security=%6eone"),
            base.replace("type=tcp", "type=tcp&type=tcp"), base.replace("type=tcp", "flow="),
            base + "&flow=", base + "&", base.replace("&encryption=none", ""), base.replace("&", ";"))
        invalid.forEach { uri -> reject(source().apply { fixture(this).put("original", uri) }) }
    }

    @Test fun healthOnlyAcceptsLiteralOwnedTargetPortEightyAndExactNoncePath() {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val url = "http://198.18.0.2/owned/$nonce"
        ClientSteerFixture.parse(source(), url)
        ClientSteerFixture.parse(source(), url.replace(".2/", ".2:80/"))
        for (invalid in listOf(url.replace("http:", "https:"), url.replace("http:", "HTTP:"),
            url.replace("198.18.0.2", "198.18.0.3"), url.replace("198.18.0.2", "192.168.1.20"),
            url.replace("198.18.0.2", "localhost"), url.replace("198.18.0.2", "user@198.18.0.2"),
            url.replace("198.18.0.2", "[::1]"), url.replace(".2/", ".2:81/"), url.replace(".2/", ".2:080/"),
            url + "?x=1", url + "?", url + "#", url + "#fragment", url + "/", url + "\n", " " + url,
            url.replace("/owned/", "/%6fwned/"), url.replace("/owned/", "/owned/../owned/"),
            url.dropLast(1), url + "0", url.replace(nonce, "A" + nonce.drop(1)), url.replace(nonce, "g".repeat(32)))) reject(source(), invalid)
    }

    @Test fun envelopeRejectsExtraMissingAndWrongTypeFields() {
        reject(JSONObject())
        reject(source().put("other", false))
        reject(JSONObject().put("mcrf_steer_fixture", JSONObject.NULL))
        reject(JSONObject().put("mcrf_steer_fixture", JSONArray()))
        reject(JSONObject().put("mcrf_steer_fixture", "{}"))
        for (key in listOf("original", "snapshot", "dialIpv4")) {
            reject(source().apply { fixture(this).remove(key) })
            reject(source().apply { fixture(this).put(key, JSONObject.NULL) })
            reject(source().apply { fixture(this).put(key, true) })
        }
        reject(source().apply { fixture(this).put("extra", false) })
        reject(source().apply { fixture(this).put("snapshot", JSONObject(PINNED_SNAPSHOT).toString()) })
    }

    private fun paths(value: Any, path: List<Any> = emptyList()): List<Pair<List<Any>, Any>> =
        listOf(path to value) + when (value) {
            is JSONObject -> value.keys().asSequence().toList().flatMap { paths(value.get(it), path + it) }
            is JSONArray -> (0 until value.length()).flatMap { paths(value.get(it), path + it) }
            else -> emptyList()
        }

    private fun at(value: Any, path: List<Any>): Any = path.fold(value) { parent, key ->
        if (parent is JSONObject) parent.get(key as String) else (parent as JSONArray).get(key as Int)
    }

    private fun replaceSnapshot(path: List<Any>, replacement: Any): JSONObject = source().apply {
        val envelope = fixture(this)
        if (path.isEmpty()) envelope.put("snapshot", replacement)
        else {
            val parent = at(envelope.getJSONObject("snapshot"), path.dropLast(1))
            val key = path.last()
            if (parent is JSONObject) parent.put(key as String, replacement) else (parent as JSONArray).put(key as Int, replacement)
        }
    }

    @Test fun everyScalarIncludingInactiveDefaultsIsPinnedByValueAndType() {
        for ((path, value) in paths(JSONObject(PINNED_SNAPSHOT))) {
            val changed: Any = when (value) {
                is Boolean -> !value
                is Int -> value + 1
                is String -> value + "changed"
                else -> continue
            }
            reject(replaceSnapshot(path, changed))
            for (wrong in listOf(JSONObject.NULL, JSONObject(), JSONArray(), if (value is String) false else "${value}")) {
                reject(replaceSnapshot(path, wrong))
            }
            if (value is Int) {
                reject(replaceSnapshot(path, value.toDouble()))
                reject(replaceSnapshot(path, value.toLong()))
            }
        }
    }

    @Test fun everyObjectRejectsMissingOrAdditionalKeysAndEveryArrayIsFixed() {
        for ((path, value) in paths(JSONObject(PINNED_SNAPSHOT))) when (value) {
            is JSONObject -> {
                for (key in value.keys().asSequence().toList()) {
                    reject(replaceSnapshot(path, JSONObject(value.toString()).apply { remove(key) }))
                }
                reject(replaceSnapshot(path, JSONObject(value.toString()).put("unknown", false)))
                reject(replaceSnapshot(path, JSONArray()))
            }
            is JSONArray -> {
                reject(replaceSnapshot(path, JSONArray(value.toString()).put(false)))
                reject(replaceSnapshot(path, JSONObject()))
                if (value.length() > 0) {
                    reject(replaceSnapshot(path, JSONArray()))
                    reject(replaceSnapshot(path, JSONArray().put(JSONObject.NULL)))
                }
            }
        }
    }

    @Test fun dnsCatchAllRuleAndInactivePolicyExpansionsAreRefusedBeforeProjection() {
        val source = source()
        val routing = fixture(source).getJSONObject("snapshot").getJSONObject("routing")
        assertEquals("block", routing.getString("defaultTarget"))
        for (target in listOf("direct", "proxy")) reject(source().apply {
            fixture(this).getJSONObject("snapshot").getJSONObject("routing").put("defaultTarget", target)
        })
        for (ip in listOf("8.8.8.8", "1.1.1.1", "10.89.0.1", "192.168.1.20")) reject(source().apply {
            fixture(this).getJSONObject("snapshot").getJSONObject("routing").getJSONObject("globalDns").put("custom", ip)
        })
        for (text in listOf("IP-CIDR,198.18.0.2/31", "IP-CIDR,0.0.0.0/0", "DOMAIN,example.invalid", "IP-CIDR,198.18.0.3/32")) reject(source().apply {
            fixture(this).getJSONObject("snapshot").getJSONObject("routing").getJSONArray("rules").getJSONObject(0).put("text", text)
        })
        for (path in listOf(listOf("routing", "advancedDns", "enabled"), listOf("routing", "remoteRouting", "adblock"),
            listOf("routing", "remoteRouting", "autoRefresh"), listOf("tun", "ipv6"), listOf("tun", "mixedEnabled"),
            listOf("tun", "httpProxy"), listOf("tun", "shareLan"), listOf("coreTuning", "apiEnabled"),
            listOf("coreTuning", "ntpEnabled"), listOf("coreTuning", "fragmentDefault"))) reject(replaceSnapshot(path, true))
    }

    companion object {
        // Independent copy of the public contract fixture; never read stores or runtime files.
        private const val PINNED_SNAPSHOT = """{"schema":1,"mode":"vpn","acceptSteerIpv4DnsModel":true,"additionalRequiredFeatures":[],"routing":{"version":1,"defaultTarget":"block","globalDns":{"provider":"Свой","protocol":"UDP","custom":"198.18.0.254"},"excludedApps":[],"vpnApps":[],"rules":[{"id":"owned-target","name":"Owned TCP fixture target","enabled":true,"mode":"addresses","target":"proxy","invert":false,"text":"IP-CIDR,198.18.0.2/32"}],"appMode":"all","advancedDns":{"enabled":false,"direct":{"provider":"Cloudflare","protocol":"DoH","custom":""},"local":{"provider":"Системный","protocol":"Системный"},"final":"remote","remoteNoAAAA":false,"directNoAAAA":false,"fakeIp":false,"fakeNoAAAA":true,"hosts":false,"answers":"","cache":true,"capacity":1024,"optimistic":false,"optimisticTimeout":"30s","timeout":"10s","strategy":"prefer_ipv4","custom":""},"geoData":{"source":"Loyalsoldier","geoipUrl":"https://example.invalid/geoip.dat","geositeUrl":"https://example.invalid/geosite.dat","selectors":[]},"remoteRouting":{"mirror":"origin","mirrorUrl":"","adblock":false,"adblockUrl":"https://example.invalid/ads.srs","autoRefresh":false,"intervalMinutes":360,"profiles":[]}},"tun":{"mtu":1500,"ipv6":false,"address4":"10.89.0.2/32","address6":"fdfe:dcba:9876::1/126","bypassPrivate":false,"privateRanges":"10.0.0.0/8","bypassDirectIps":false,"mixedEnabled":false,"randomPort":false,"mixedPort":10808,"httpProxy":false,"httpBypass":"","shareLan":false,"lanPort":10809,"dnsPort":0,"customInbounds":""},"coreTuning":{"certificateStore":"system","insecure":false,"ntpEnabled":false,"ntpServer":"time.cloudflare.com","ntpPort":123,"ntpInterval":"30m","clashEnabled":false,"clashPort":9090,"clashSecret":"","apiEnabled":false,"apiPort":9091,"apiSecret":"","muxDefault":false,"muxProtocol":"smux","muxConcurrency":8,"muxPadding":false,"fragmentDefault":false,"fragmentDelay":"500ms","fingerprint":"","idleTimeout":"","keepAlive":"","streamWindow":"","connectionWindow":"","maxStreams":0,"initialPacketSize":0,"disablePmtu":false}}"""
    }
}
