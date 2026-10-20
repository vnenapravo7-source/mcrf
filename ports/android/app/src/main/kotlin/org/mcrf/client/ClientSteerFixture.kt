package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.steerprep.SteerMcrfSnapshotAdapter

/** Pure admission of the bounded synthetic fixture, not a general profile importer.
 * The caller owns DEBUG/fixture-mode, private canonical input, fresh UUID/nonce,
 * installed-worker validation and all device/network/lifetime gates.
 */
object ClientSteerFixture {
    // Strings only: neither the mutable caller JSON nor the adapter Result escapes.
    // Deliberately not a data class: toString must not disclose the private URI.
    class Result internal constructor(
        val original: String,
        val settingsJson: String,
        val dialIpv4: String,
        val snapshotJson: String
    )

    @JvmStatic fun parse(source: JSONObject, healthUrl: String): Result {
        require(keys(source, setOf("mcrf_steer_fixture"))) { "STEER_FIXTURE_ENVELOPE" }
        val fixture = source.opt("mcrf_steer_fixture") as? JSONObject
        require(fixture != null && keys(fixture, setOf("original", "snapshot", "dialIpv4"))) {
            "STEER_FIXTURE_FIELDS"
        }
        val original = fixture.opt("original") as? String
        val dial = fixture.opt("dialIpv4") as? String
        require(original != null && dial != null && privateIpv4(dial)) { "STEER_FIXTURE_ENDPOINT" }
        require(original.length <= 256) { "STEER_FIXTURE_URI" }
        val match = originalPattern.matchEntire(original)
        require(match != null && match.groupValues[1] == dial &&
            match.groupValues[2].toInt() in 1..65535 &&
            match.groupValues[3].split('&').let { parts ->
                parts.size == 3 && parts.toSet() == setOf("security=none", "type=tcp", "encryption=none")
            }) { "STEER_FIXTURE_URI" }
        require(healthPattern.matches(healthUrl)) { "STEER_FIXTURE_HEALTH" }

        val expected = JSONObject(SYNTHETIC_SNAPSHOT_JSON)
        val snapshot = fixture.opt("snapshot")
        // Two exact, detached synthetic models. This does NOT negotiate a new
        // capability: the isolated module must still advertise and validate
        // model2 itself before acquiring a TUN or starting native code.
        val admitted = if (same(expected, snapshot)) expected else if(same(p1Snapshot(),snapshot))p1Snapshot()
            else listOf("DoT","DoH").map {p1TlsSnapshot(it)}.firstOrNull {same(it,snapshot)}?:p1ProxySnapshot(healthUrl.substringAfterLast('/'))
        require(same(admitted, snapshot)) { "STEER_FIXTURE_SNAPSHOT" }
        // Detach before conversion, then validate again so no mutable input is
        // retained or used by the canonical adapter after its admission check.
        val snapshotJson = (snapshot as JSONObject).toString()
        require(same(admitted, JSONObject(snapshotJson))) { "STEER_FIXTURE_SNAPSHOT" }
        val converted = SteerMcrfSnapshotAdapter.convert(snapshotJson)
        require(converted.supported && converted.unsupported.isEmpty() && converted.settingsJson != null) {
            "STEER_FIXTURE_UNSUPPORTED"
        }
        return Result(original, converted.settingsJson, dial, snapshotJson)
    }

    /** SOURCE-ONLY common-P1 native DNS/dual-stack fixture, no mutable singleton.
     * The coordinator binds its nonce, private peer, APK hashes and whole-P1
     * readiness separately. No arbitrary profiles, rules, resolver or app list.
     * Every non-owned destination remains BLOCK, including resolver IPs: only
     * the module's original-owner-managed DNS control may use these endpoints. */
    @JvmStatic fun requireP1Snapshot(value:JSONObject) { require(same(p1Snapshot(),value)) {"STEER_P1_EXACT_SNAPSHOT"} }
    @JvmStatic fun requireP1TlsSnapshot(value:JSONObject,protocol:String){require(same(p1TlsSnapshot(protocol),value)){"STEER_P1_TLS_EXACT_SNAPSHOT"}}
    @JvmStatic fun p1TlsSnapshot(protocol:String):JSONObject {
        require(protocol in setOf("DoT","DoH")){"STEER_P1_TLS_PROTOCOL"}
        return p1Snapshot().apply {
            val routing=getJSONObject("routing")
            routing.put("globalDns",JSONObject().put("provider","Cloudflare").put("protocol",protocol).put("custom",""))
            val rules=routing.getJSONArray("rules")
            rules.getJSONObject(1).put("dns",JSONObject().put("provider","Google").put("protocol",protocol).put("custom",""))
            rules.put(JSONObject().put("id","owned-trust-dns").put("name","Owned untrusted DNS")
                .put("enabled",true).put("mode","addresses").put("target","proxy").put("invert",false).put("text","DOMAIN-SUFFIX,trust.owned.invalid")
                .put("dns",JSONObject().put("provider","Свой").put("protocol",protocol)
                    .put("custom",if(protocol=="DoH")"https://198.18.0.253:1443/dns-query"else"tls://198.18.0.253:1853")))
        }
    }
    @JvmStatic fun requireP1ProxySnapshot(value:JSONObject,nonce:String) {require(same(p1ProxySnapshot(nonce),value)){"STEER_P1_PROXY_EXACT_SNAPSHOT"}}
    @JvmStatic fun p1ProxySnapshot(nonce:String):JSONObject {
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        return p1Snapshot().apply {
            getJSONObject("tun").put("mixedEnabled",true).put("randomPort",true)
            put("localProxyAuth",JSONObject().put("enabled",true).put("username","p1-owned").put("password",nonce))
            val original=getJSONObject("routing").getJSONArray("rules")
            val rules=JSONArray().put(JSONObject().put("id","owned-main-control").put("name","Owned main control")
                .put("enabled",true).put("mode","addresses").put("target","proxy").put("invert",false).put("text","")
                .put("nativeMatch",JSONObject().put("package_name",JSONArray().put("org.mcrf.client"))
                    .put("ip_cidr",JSONArray().put("198.18.0.2/32").put("fd77::2/128"))
                    .put("port",JSONArray().put(80)).put("network",JSONArray().put("tcp"))))
                .put(JSONObject().put("id","owned-helper-request").put("name","Owned original helper request")
                    .put("enabled",true).put("mode","addresses").put("target","proxy").put("invert",false).put("text","")
                    .put("nativeMatch",JSONObject().put("package_name",JSONArray().put("org.mcrf.p1owner.peer"))
                        .put("ip_cidr",JSONArray().put("198.18.0.3/32").put("fd77::3/128"))
                        .put("port",JSONArray().put(80)).put("network",JSONArray().put("tcp"))))
                .put(JSONObject().put("id","owned-helper-dns").put("name","Owned helper managed DNS")
                    .put("enabled",true).put("mode","addresses").put("target","proxy").put("invert",false).put("text","")
                    .put("nativeMatch",JSONObject().put("package_name",JSONArray().put("org.mcrf.p1owner.peer"))
                        .put("ip_cidr",JSONArray().put("198.18.0.254/32")).put("port",JSONArray().put(53)).put("network",JSONArray().put("udp"))))
            // DNS plans remain the SAME exact owned synthetic global/rule pair.
            for(index in 1 until original.length())rules.put(original.getJSONObject(index))
            getJSONObject("routing").put("rules",rules)
        }
    }
    @JvmStatic fun p1Snapshot(): JSONObject = JSONObject(SYNTHETIC_SNAPSHOT_JSON).apply {
        put("schema", 2); remove("acceptSteerIpv4DnsModel"); put("acceptSteerDnsModel", true)
        getJSONObject("tun").put("ipv6", true)
        getJSONObject("routing").put("rules", JSONArray()
            .put(JSONObject().put("id", "owned-target").put("name", "Owned dual-stack TCP target")
                .put("enabled", true).put("mode", "addresses").put("target", "proxy").put("invert", false)
                .put("text", "IP-CIDR,198.18.0.2/32\nIP-CIDR,fd77::2/128"))
            .put(JSONObject().put("id", "owned-rule-dns").put("name", "Owned separate rule DNS")
                .put("enabled", true).put("mode", "addresses").put("target", "proxy").put("invert", false)
                .put("text", "DOMAIN-SUFFIX,rule.owned.invalid")
                .put("dns", JSONObject().put("provider", "Свой").put("protocol", "UDP").put("custom", "198.18.0.253")))
            .put(JSONObject().put("id", "owned-global-dns").put("name", "Owned global DNS")
                .put("enabled", true).put("mode", "addresses").put("target", "proxy").put("invert", false)
                .put("text", "DOMAIN-SUFFIX,global.owned.invalid")))
    }

    private fun keys(value: JSONObject, expected: Set<String>): Boolean =
        value.length() == expected.size && value.keys().asSequence().toSet() == expected

    /** Object key order is irrelevant; arrays, scalar values and JVM JSON scalar
     * types must match exactly. No coercion, JSONObject.similar or defaulting.
     */
    private fun same(expected: Any?, actual: Any?): Boolean = when (expected) {
        is JSONObject -> actual is JSONObject &&
            keys(actual, expected.keys().asSequence().toSet()) &&
            expected.keys().asSequence().all { same(expected.opt(it), actual.opt(it)) }
        is JSONArray -> actual is JSONArray && expected.length() == actual.length() &&
            (0 until expected.length()).all { same(expected.opt(it), actual.opt(it)) }
        else -> expected != null && actual != null && expected.javaClass == actual.javaClass && expected == actual
    }

    private fun privateIpv4(value: String): Boolean {
        if (value.length !in 7..15) return false
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { !octetPattern.matches(it) }) return false
        val octets = parts.map { it.toInt() }
        if (octets.any { it > 255 }) return false
        return octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] == 168
    }

    private val octetPattern = Regex("0|[1-9][0-9]{0,2}")
    private val originalPattern = Regex(
        "vless://[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}" +
            "@([0-9.]+):([1-9][0-9]{0,4})\\?([^?#/]+)"
    )
    private val healthPattern = Regex("http://198\\.18\\.0\\.2(?::80)?/owned/[0-9a-f]{32}")

    /** Public synthetic schema1 fixture from bounded-vless-fixture-schema1/
     * synthetic-full-snapshot.json. Includes the exact inactive defaults too.
     * Contains no private run identity, profile, nonce or payload.
     */
    const val SYNTHETIC_SNAPSHOT_JSON = """{
      "schema":1,"mode":"vpn","acceptSteerIpv4DnsModel":true,"additionalRequiredFeatures":[],
      "routing":{
        "version":1,"defaultTarget":"block",
        "globalDns":{"provider":"Свой","protocol":"UDP","custom":"198.18.0.254"},
        "excludedApps":[],"vpnApps":[],
        "rules":[{"id":"owned-target","name":"Owned TCP fixture target","enabled":true,
          "mode":"addresses","target":"proxy","invert":false,"text":"IP-CIDR,198.18.0.2/32"}],
        "appMode":"all",
        "advancedDns":{"enabled":false,"direct":{"provider":"Cloudflare","protocol":"DoH","custom":""},
          "local":{"provider":"Системный","protocol":"Системный"},"final":"remote",
          "remoteNoAAAA":false,"directNoAAAA":false,"fakeIp":false,"fakeNoAAAA":true,
          "hosts":false,"answers":"","cache":true,"capacity":1024,"optimistic":false,
          "optimisticTimeout":"30s","timeout":"10s","strategy":"prefer_ipv4","custom":""},
        "geoData":{"source":"Loyalsoldier","geoipUrl":"https://example.invalid/geoip.dat",
          "geositeUrl":"https://example.invalid/geosite.dat","selectors":[]},
        "remoteRouting":{"mirror":"origin","mirrorUrl":"","adblock":false,
          "adblockUrl":"https://example.invalid/ads.srs","autoRefresh":false,"intervalMinutes":360,"profiles":[]}
      },
      "tun":{"mtu":1500,"ipv6":false,"address4":"10.89.0.2/32","address6":"fdfe:dcba:9876::1/126",
        "bypassPrivate":false,"privateRanges":"10.0.0.0/8","bypassDirectIps":false,
        "mixedEnabled":false,"randomPort":false,"mixedPort":10808,"httpProxy":false,"httpBypass":"",
        "shareLan":false,"lanPort":10809,"dnsPort":0,"customInbounds":""},
      "coreTuning":{"certificateStore":"system","insecure":false,"ntpEnabled":false,
        "ntpServer":"time.cloudflare.com","ntpPort":123,"ntpInterval":"30m",
        "clashEnabled":false,"clashPort":9090,"clashSecret":"","apiEnabled":false,"apiPort":9091,"apiSecret":"",
        "muxDefault":false,"muxProtocol":"smux","muxConcurrency":8,"muxPadding":false,
        "fragmentDefault":false,"fragmentDelay":"500ms","fingerprint":"","idleTimeout":"","keepAlive":"",
        "streamWindow":"","connectionWindow":"","maxStreams":0,"initialPacketSize":0,"disablePmtu":false}
    }"""
}
