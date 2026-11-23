package org.mcrf.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Input for the owned Android native-service acceptance. Only a pinned test module can run it. */
internal class BsNativePhysicalServiceFixture private constructor(
    val moduleSha256: String,
    val generation: String,
    val nonce: String,
    val source: JSONObject,
    val profile: JSONObject,
    val quicOriginPort: Int?
) {
    fun auth(): JSONObject = JSONObject().put("enabled", true).put("username", "owned-bs")
        .put("password", nonce)

    fun restrictTun(document: JSONObject, packageName: String) {
        val inbounds = document.getJSONArray("inbounds")
        val tun = (0 until inbounds.length()).map(inbounds::getJSONObject)
            .single { it.getString("type") == "tun" }
        tun.put("include_package", JSONArray().put(packageName))
        val routes = JSONArray().put("198.19.255.53/32").put("fdfe:dcba:9876:dd54::53/128")
        if (quicOriginPort != null) routes.put(QUIC_ROUTE)
        tun.put("route_address", routes)
    }

    companion object {
        private const val QUIC_ROUTE = "198.18.0.2/32"
        private const val QUIC_PORT = 18444

        internal fun fixtureSource(profile: JSONObject, quicOriginPort: Int? = null): JSONObject = JSONObject()
            .put("outbounds", JSONArray()
                .put(JSONObject().put("type", "socks").put("tag", "carrier").put("server", "127.0.0.1")
                    .put("server_port", 1).put("version", "5"))
                .put(JSONObject().put("type", "direct").put("tag", "direct"))
                .put(JSONObject().put("type", "block").put("tag", "owned-fixture-block")))
            .put("route", JSONObject().put("final", "owned-fixture-block").put("rules", JSONArray().apply {
                if (quicOriginPort != null) {
                    require(quicOriginPort in 1024..65535)
                    put(JSONObject().put("action", "route-options").put("network", "udp")
                        .put("ip_cidr", JSONArray().put(QUIC_ROUTE)).put("port", QUIC_PORT)
                        .put("override_address", "127.0.0.1").put("override_port", quicOriginPort))
                    // The native router evaluates the next rule on the rewritten destination.
                    put(JSONObject().put("action", "route").put("network", "udp").put("outbound", "direct")
                        .put("ip_cidr", JSONArray().put("127.0.0.1/32")).put("port", quicOriginPort))
                }
            }.put(JSONObject().put("action", "sniff"))
                .put(JSONObject().put("action", "hijack-dns").put("protocol", "dns"))
                .put(JSONObject().put("action", "route").put("outbound", "direct")
                    .put("ip_cidr", JSONArray().put("127.0.0.1/32").put("::1/128")))))
            .put("dns", JSONObject().put("servers", JSONArray()
                .put(JSONObject().put("type", "hosts").put("tag", "owned")
                    .put("predefined", JSONObject().put("owned.invalid", "198.51.100.9"))))
                .put("final", "owned"))
            .put("mcrf_carrier", JSONObject(profile.toString()).put("outbound_tag", "carrier"))

        fun read(context: Context, file: File, data: JSONObject): BsNativePhysicalServiceFixture? {
            if (!data.has("bsNativePhysical")) return null
            check(BuildConfig.DEBUG && Build.SUPPORTED_ABIS.first() == "arm64-v8a" &&
                Build.HARDWARE !in setOf("ranchu", "goldfish") && !Build.FINGERPRINT.startsWith("generic"))
            require(data.keys().asSequence().toSet() == setOf("bsNativePhysical"))
            val settings = data.getJSONObject("bsNativePhysical")
            val fields = setOf("nonce", "createdElapsed", "generation", "moduleSha256")
            val keys = settings.keys().asSequence().toSet()
            require(keys == fields || keys == fields + setOf("quicOriginPort", "quicPairSha256"))
            val quicOriginPort = if (settings.has("quicOriginPort")) {
                val port = settings.opt("quicOriginPort") as? Int ?: error("BS_PHYSICAL_QUIC_PORT")
                require(port in 1024..65535) { "BS_PHYSICAL_QUIC_PORT" }
                ClientP1OwnerFixtureScope.packages(context, settings.getString("quicPairSha256"))
                port
            } else null
            val nonce = settings.getString("nonce")
            require(nonce.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) &&
                file.parentFile?.name == "mcrf-test-$nonce" && file.length() in 1L..4096L)
            val now = SystemClock.elapsedRealtime()
            require(settings.getLong("createdElapsed") in (now - 60_000)..now)
            val generation = settings.getString("generation")
            require(generation in setOf("2.5.1", "2.5.3"))
            val pin = settings.getString("moduleSha256")
            require(pin.matches(Regex("[0-9a-f]{64}")))
            val module = BsEnginePackage.installation(context)
            require(module.flags and ApplicationInfo.FLAG_TEST_ONLY != 0 &&
                module.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                module.splitSourceDirs.isNullOrEmpty() && BsNativeContract.pin(context) == pin) {
                "BS_PHYSICAL_FIXTURE_MODULE"
            }
            val profile = checkNotNull(Csqtt25Profile.fromJson("owned-service", JSONObject()
                .put("type", "csqtt").put("csqtt_generation", "2.5")
                .put("csqtt_implementation", "swg-panel-$generation").put("server", "192.0.2.1")
                .put("server_port", 9).put("password", "owned-fixture-only").put("workers", 10)
                .put("hashes", JSONArray().put("owned_fixture_only_123456"))).carrier)
            val moduleNative = BsNativeContract.installation(context, profile, pin).executable
            val markers = listOf("OWNED_BOOTSTRAP_CLOSED", "OWNED_BOOTSTRAP_BUFFER_LEAK")
            require(moduleNative.inputStream().use { BsNativeContract.hasMarkers(it, markers) }) {
                "BS_PHYSICAL_FIXTURE_BOOTSTRAP_MODULE"
            }
            return BsNativePhysicalServiceFixture(pin, generation, nonce, fixtureSource(profile, quicOriginPort),
                profile, quicOriginPort)
        }
    }
}
