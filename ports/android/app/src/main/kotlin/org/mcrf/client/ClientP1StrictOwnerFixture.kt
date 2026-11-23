package org.mcrf.client

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Closed debug input for exercising the production owner gate with disposable apps. */
internal class ClientP1StrictOwnerFixture private constructor(
    val includedPackages: List<String>,
    val excludedPackages: List<String>,
    val source: JSONObject,
    val healthUrl: String,
    val lan: Lan?
) {
    data class Lan(val port:Int,val originPort:Int,val password:String) {
        fun configure(source:JSONObject,localPort:Int):JSONObject =
            ClientTunConfig.apply(source,ClientTunSettings(mixedEnabled=false,shareLan=true,lanPort=port),
                JSONObject().put("enabled",true).put("username",LAN_USERNAME).put("password",password),localPort).config
    }

    companion object {
        const val IPV4 = "198.18.0.2"
        const val IPV6 = "2001:db8::2"
        const val PORT = 18443
        const val QUIC_PORT = 18444
        const val LAN_TARGET = "198.18.0.3"
        const val LAN_USERNAME = "p1-owned"
        fun dnsName(nonce: String) = "owned-$nonce.fixture.test"

        fun read(context: Context, file: File, data: JSONObject): ClientP1StrictOwnerFixture? {
            if (!data.has("p1StrictOwner")) return null
            check(BuildConfig.DEBUG)
            require(data.keys().asSequence().toSet() == setOf("p1StrictOwner"))
            val input = data.getJSONObject("p1StrictOwner")
            val expected = setOf("nonce", "createdElapsed", "pairSha256", "scope", "ports")
            val keys=input.keys().asSequence().toSet()
            require(keys == expected || keys == expected + "lan" || keys == expected + "quicPort")
            val nonce = input.getString("nonce")
            require(nonce.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
            require(file.parentFile?.name == "mcrf-test-$nonce" && file.length() in 1L..4096L)
            val now = SystemClock.elapsedRealtime()
            require(input.getLong("createdElapsed") in (now - 60_000)..now) { "P1_STRICT_OWNER_FIXTURE_EXPIRED" }
            val packages = ClientP1OwnerFixtureScope.packages(context, input.getString("pairSha256"))
            val included: List<String>
            val excluded: List<String>
            when (input.getString("scope")) {
                "both" -> {
                    included = packages
                    excluded = emptyList()
                }
                "partial" -> {
                    included = packages.take(2)
                    excluded = emptyList()
                }
                "excluded" -> {
                    included = packages
                    excluded = listOf(packages.last())
                }
                else -> error("P1_STRICT_OWNER_SCOPE")
            }
            val ports = input.getJSONArray("ports")
            require(ports.length() == 4)
            val originPorts = List(4) { ports.getInt(it).also { port -> require(port in 1024..65535) } }
            val tag = "fixture-owned-$nonce"
            val rules = JSONArray()
            originPorts.forEachIndexed { index, port ->
                val ipv4 = index < 2
                rules.put(JSONObject().put("inbound", JSONArray().put("tun-in"))
                    .put("network", if (index % 2 == 0) "tcp" else "udp")
                    .put("ip_cidr", JSONArray().put(if (ipv4) "$IPV4/32" else "$IPV6/128"))
                    .put("port", PORT).put("action", "route").put("outbound", tag)
                    .put("override_address", if (ipv4) "127.0.0.1" else "::1")
                    .put("override_port", port))
            }
            val lan=input.optJSONObject("lan")?.let { requested ->
                require(input.getString("scope")=="excluded"&&requested.keys().asSequence().toSet()==setOf("port","originPort","password"))
                val port=requested.getInt("port")
                val originPort=requested.getInt("originPort")
                val password=requested.getString("password")
                require(port in 1024..65535&&originPort in 1024..65535&&port!=originPort&&
                    port !in originPorts&&originPort !in originPorts&&password.matches(Regex("[0-9a-f]{48}")))
                Lan(port,originPort,password)
            }
            require(input.has("lan")== (lan!=null))
            if(lan!=null)rules.put(JSONObject().put("inbound",JSONArray().put("mcrf-lan"))
                .put("network","tcp").put("ip_cidr",JSONArray().put("$LAN_TARGET/32")).put("port",80)
                .put("action","route").put("outbound",tag).put("override_address","127.0.0.1").put("override_port",lan.originPort))
            if (input.has("quicPort")) {
                val port = input.get("quicPort")
                require(input.getString("scope") == "excluded" && port is Int &&
                    port in 1024..65535 && port != originPorts[1])
                rules.put(JSONObject().put("inbound", JSONArray().put("tun-in")).put("network", "udp")
                    .put("ip_cidr", JSONArray().put("$IPV4/32")).put("port", QUIC_PORT)
                    .put("action", "route").put("outbound", tag)
                    .put("override_address", "127.0.0.1").put("override_port", port))
            }
            rules.put(JSONObject().put("action", "reject"))
            val dnsName = dnsName(nonce)
            val source = JSONObject()
                .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", tag)))
                .put("route", JSONObject().put("final", tag).put("rules", rules))
                .put("dns", JSONObject()
                    .put("servers", JSONArray().put(JSONObject().put("type", "hosts").put("tag", "fixture-dns")
                        .put("predefined", JSONObject().put(dnsName, IPV4))))
                    .put("final", "fixture-dns").put("rules", JSONArray()
                        .put(JSONObject().put("domain", JSONArray().put(dnsName)).put("action", "route").put("server", "fixture-dns"))
                        .put(JSONObject().put("action", "reject"))))
            return ClientP1StrictOwnerFixture(included, excluded, source, "http://127.0.0.1:${originPorts.first()}/$nonce",lan)
        }
    }
}
