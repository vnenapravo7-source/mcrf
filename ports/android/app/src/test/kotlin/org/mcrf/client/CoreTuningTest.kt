package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreTuningTest {
    @Test fun editingRetainedFieldPreservesLegacySettingsWithoutVisibleControls() {
        val old=CoreTuningSettings(ntpEnabled=true,apiEnabled=true,apiSecret="0123456789abcdef",clashEnabled=true,clashSecret="fedcba9876543210")
        val edited=CoreTuningSettings.fromJson(old.json().put("streamWindow","1048576"))
        assertEquals(old.copy(streamWindow="1048576"),edited)
        val generated=CoreTuningConfig.apply(JSONObject().put("outbounds",JSONArray()),edited)
        assertTrue(generated.has("ntp"))
        assertEquals(1,generated.getJSONArray("services").length())
        assertTrue(generated.getJSONObject("experimental").has("clash_api"))
    }
    private fun config(vararg outputs: JSONObject) = JSONObject().put("outbounds",JSONArray(outputs.toList()))
    private fun output(type: String = "vless") = JSONObject().put("type",type).put("tag","proxy").put("tls",JSONObject().put("enabled",true))
    private fun reject(run: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { run() } }
    @Test fun defaultRoundTripAndSafety() { val s = CoreTuningSettings(); assertEquals(s,CoreTuningSettings.fromJson(s.json())); assertFalse(s.insecure); assertFalse(s.apiEnabled); assertFalse(s.clashEnabled) }
    @Test fun enabledApiRequiresSecret() { reject { CoreTuningSettings(apiEnabled=true).validate() }; reject { CoreTuningSettings(clashEnabled=true).validate() } }
    @Test fun apiMapsServicesNotV2rayStats() { val c = CoreTuningConfig.apply(config(),CoreTuningSettings(apiEnabled=true,apiSecret="0123456789abcdef")); val api = c.getJSONArray("services").getJSONObject(0); assertEquals("api",api.getString("type")); assertEquals("127.0.0.1",api.getString("listen")); assertFalse(c.toString().contains("v2ray_api")) }
    @Test fun clashKeepsDnsCacheSettings() { val c = config().put("experimental",JSONObject().put("cache_file",JSONObject().put("enabled",true))); val applied = CoreTuningConfig.apply(c,CoreTuningSettings(clashEnabled=true,clashSecret="0123456789abcdef")); assertTrue(applied.getJSONObject("experimental").has("cache_file")); assertEquals("127.0.0.1:9090",applied.getJSONObject("experimental").getJSONObject("clash_api").getString("external_controller")) }
    @Test fun ntpNeverWritesAndroidClock() { val c = CoreTuningConfig.apply(config(),CoreTuningSettings(ntpEnabled=true)); assertFalse(c.getJSONObject("ntp").getBoolean("write_to_system")) }
    @Test fun rejectsZeroNegativeAndMalformedDuration() { listOf("0s","-1s","1","NaNs","1day").forEach { bad -> reject { CoreTuningSettings(ntpInterval=bad).validate() } }; CoreTuningSettings(ntpInterval="1.5h").validate() }
    @Test fun muxDefaultsRespectExplicitProfile() { val out = output().put("multiplex",JSONObject().put("enabled",false).put("max_streams",3)); val c = CoreTuningConfig.apply(config(out),CoreTuningSettings(muxDefault=true)); assertFalse(c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("multiplex").getBoolean("enabled")) }
    @Test fun muxDoesNotApplyToVisionOrQuic() { val c = CoreTuningConfig.apply(config(output().put("flow","xtls-rprx-vision"),output("hysteria2")),CoreTuningSettings(muxDefault=true)); repeat(2) { assertFalse(c.getJSONArray("outbounds").getJSONObject(it).has("multiplex")) } }
    @Test fun builtinFragmentNeverEmitsCustomFields() { val c = CoreTuningConfig.apply(config(output()),CoreTuningSettings(fragmentDefault=true)); val tls = c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls"); assertTrue(tls.getBoolean("fragment")); assertFalse(c.toString().contains("fragment_size")); assertFalse(c.toString().contains("tls_tricks")) }
    @Test fun explicitFragmentAndUtlsPreserved() { val out = output(); out.getJSONObject("tls").put("fragment",false).put("utls",JSONObject().put("enabled",false)); val c = CoreTuningConfig.apply(config(out),CoreTuningSettings(fragmentDefault=true,fingerprint="chrome")); val tls = c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls"); assertFalse(tls.getBoolean("fragment")); assertFalse(tls.getJSONObject("utls").getBoolean("enabled")) }
    @Test fun quicFieldsOnlyOnSupportedProtocols() { val c = CoreTuningConfig.apply(config(output("hysteria2"),output("masque"),output()),CoreTuningSettings(streamWindow="1048576",initialPacketSize=1300,disablePmtu=true)); assertEquals(1048576,c.getJSONArray("outbounds").getJSONObject(0).getLong("stream_receive_window")); assertFalse(c.getJSONArray("outbounds").getJSONObject(1).has("initial_packet_size")); assertFalse(c.getJSONArray("outbounds").getJSONObject(2).has("stream_receive_window")) }
    @Test fun explicitWindowPreserved() { val c = CoreTuningConfig.apply(config(output("tuic").put("stream_receive_window",98765)),CoreTuningSettings(streamWindow="1048576")); assertEquals(98765,c.getJSONArray("outbounds").getJSONObject(0).getInt("stream_receive_window")) }
    @Test fun mozillaStorePreservesCustomCertificates() { val c = config().put("certificate",JSONObject().put("certificate",JSONArray().put("test"))); val result = CoreTuningConfig.apply(c,CoreTuningSettings(certificateStore="mozilla")); assertEquals("mozilla",result.getJSONObject("certificate").getString("store")); assertEquals("test",result.getJSONObject("certificate").getJSONArray("certificate").getString(0)) }
    @Test fun httpTransportOnlyGetsSupportedIdleField() { val out = output().put("transport",JSONObject().put("type","http")); val c = CoreTuningConfig.apply(config(out),CoreTuningSettings(idleTimeout="30s",keepAlive="10s",streamWindow="100000")); val transport = c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("transport"); assertEquals("30s",transport.getString("idle_timeout")); assertFalse(transport.has("stream_receive_window")); assertFalse(transport.has("keep_alive_period")) }
    @Test fun neverMutatesSource() { val c = config(output()); val before = c.toString(); CoreTuningConfig.apply(c,CoreTuningSettings(insecure=true)); assertEquals(before,c.toString()) }
    @Test fun rejectsBadFingerprintAndWindow() { reject { CoreTuningSettings(fingerprint="imaginary").validate() }; reject { CoreTuningSettings(streamWindow="-1").validate() } }
    @Test fun durationIsBoundedAfterUnitConversion() { reject { CoreTuningSettings(ntpInterval="1000000h").validate() }; CoreTuningSettings(ntpInterval="365h").validate() }
    @Test fun tcpClientHelloDefaultsNotInjectedIntoQuicTls() { val c = CoreTuningConfig.apply(config(output("tuic")),CoreTuningSettings(fragmentDefault=true,fingerprint="chrome")); val tls = c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls"); assertFalse(tls.has("fragment")); assertFalse(tls.has("utls")) }
    @Test fun rejectsStringBooleanAndUnknownCoreSetting() { reject { CoreTuningSettings.fromJson(JSONObject().put("insecure","true")) }; reject { CoreTuningSettings.fromJson(JSONObject().put("tls_tricks",true)) } }
    @Test fun disabledApiCannotBeReenabledByProfile() { val c = config().put("services",JSONArray().put(JSONObject().put("type","api").put("listen","0.0.0.0").put("listen_port",9999))); val applied = CoreTuningConfig.apply(c,CoreTuningSettings()); assertFalse(applied.has("services")) }
    @Test fun probeTransportDefaultsApplyWithoutAdminOrNtpListeners() {
        val input = config(output(),output("hysteria2"))
            .put("inbounds",JSONArray().put(JSONObject().put("type","mixed").put("tag","probe").put("listen","127.0.0.1").put("listen_port",10999)))
            .put("ntp",JSONObject().put("enabled",true))
            .put("services",JSONArray().put(JSONObject().put("type","api")).put(JSONObject().put("type","ssm-api")))
            .put("experimental",JSONObject().put("clash_api",JSONObject()).put("v2ray_api",JSONObject()).put("debug",JSONObject()).put("cache_file",JSONObject().put("enabled",true)))
        val before = input.toString()
        val settings = CoreTuningSettings(certificateStore="mozilla",insecure=true,ntpEnabled=true,clashEnabled=true,clashSecret="fixture-secret-16",apiEnabled=true,apiSecret="fixture-secret-16",muxDefault=true,muxConcurrency=13,muxPadding=true,fragmentDefault=true,fingerprint="chrome",idleTimeout="30s",keepAlive="10s",streamWindow="1048576",connectionWindow="4194304",maxStreams=31,initialPacketSize=1300,disablePmtu=true)
        val applied = CoreTuningConfig.applyTransportOnly(input,settings)
        assertEquals(before,input.toString())
        assertEquals(input.getJSONArray("inbounds").toString(),applied.getJSONArray("inbounds").toString())
        assertFalse(applied.has("ntp")); assertFalse(applied.has("services"))
        val experimental = applied.getJSONObject("experimental")
        assertFalse(experimental.has("clash_api")); assertFalse(experimental.has("v2ray_api")); assertFalse(experimental.has("debug")); assertTrue(experimental.has("cache_file"))
        assertEquals("mozilla",applied.getJSONObject("certificate").getString("store"))
        val tcp = applied.getJSONArray("outbounds").getJSONObject(0)
        assertTrue(tcp.getJSONObject("tls").getBoolean("insecure")); assertTrue(tcp.getJSONObject("tls").getBoolean("fragment")); assertEquals("chrome",tcp.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
        assertEquals(13,tcp.getJSONObject("multiplex").getInt("max_streams")); assertTrue(tcp.getJSONObject("multiplex").getBoolean("padding"))
        val quic = applied.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("30s",quic.getString("idle_timeout")); assertEquals(1048576L,quic.getLong("stream_receive_window")); assertEquals(1300,quic.getInt("initial_packet_size")); assertTrue(quic.getBoolean("disable_path_mtu_discovery"))
        assertFalse(quic.getJSONObject("tls").has("fragment")); assertFalse(quic.getJSONObject("tls").has("utls"))
    }
    @Test fun probeTransportPassStillValidatesAndPreservesExplicitProfileDefaults() {
        reject { CoreTuningConfig.applyTransportOnly(config(),CoreTuningSettings(muxProtocol="imaginary")) }
        val out = output().put("multiplex",JSONObject().put("enabled",false))
        out.getJSONObject("tls").put("fragment",false).put("utls",JSONObject().put("enabled",false))
        val applied = CoreTuningConfig.applyTransportOnly(config(out),CoreTuningSettings(muxDefault=true,fragmentDefault=true,fingerprint="chrome"))
        val result = applied.getJSONArray("outbounds").getJSONObject(0)
        assertFalse(result.getJSONObject("multiplex").getBoolean("enabled")); assertFalse(result.getJSONObject("tls").getBoolean("fragment")); assertFalse(result.getJSONObject("tls").getJSONObject("utls").getBoolean("enabled"))
    }
    @Test fun workerDefaultsReachOriginalTlsAndMuxWithoutProcessOrFragmentFields() {
        val raw=output().put("tls_fragment",JSONObject().put("enabled",true).put("size","10-100").put("sleep","2-5"))
            .put("transport",JSONObject().put("type","http"))
        raw.getJSONObject("tls").put("tls_tricks",JSONObject().put("mixedcase_sni",true))
        val before=raw.toString()
        val result=CoreTuningConfig.applyWorkerTransportDefaults(raw,CoreTuningSettings(certificateStore="mozilla",ntpEnabled=true,insecure=true,fingerprint="chrome",muxDefault=true,muxConcurrency=12,muxPadding=true,fragmentDefault=true,idleTimeout="30s"))
        assertEquals(before,raw.toString()); assertEquals("vless",result.getString("type"))
        assertTrue(result.getJSONObject("tls").getBoolean("insecure")); assertEquals("chrome",result.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
        assertEquals(12,result.getJSONObject("multiplex").getInt("max_streams")); assertTrue(result.getJSONObject("multiplex").getBoolean("padding"))
        assertEquals("30s",result.getJSONObject("transport").getString("idle_timeout"))
        assertFalse(result.getJSONObject("tls").has("fragment")); assertEquals(raw.getJSONObject("tls_fragment").toString(),result.getJSONObject("tls_fragment").toString())
        assertTrue(result.getJSONObject("tls").getJSONObject("tls_tricks").getBoolean("mixedcase_sni"))
        listOf("ntp","certificate","services","experimental","inbounds").forEach { assertFalse(result.has(it)) }
    }
    @Test fun workerDefaultsPreserveExplicitAndRejectNativeXrayShape() {
        val raw=output().put("multiplex",JSONObject().put("enabled",false));raw.getJSONObject("tls").put("utls",JSONObject().put("enabled",false)).put("fragment",false)
        val result=CoreTuningConfig.applyWorkerTransportDefaults(raw,CoreTuningSettings(muxDefault=true,fingerprint="chrome",fragmentDefault=true))
        assertFalse(result.getJSONObject("multiplex").getBoolean("enabled")); assertFalse(result.getJSONObject("tls").getJSONObject("utls").getBoolean("enabled")); assertFalse(result.getJSONObject("tls").getBoolean("fragment"))
        reject {CoreTuningConfig.applyWorkerTransportDefaults(JSONObject().put("protocol","vless"),CoreTuningSettings())}
        reject {CoreTuningConfig.applyWorkerTransportDefaults(JSONObject().put("type","socks").put("server","127.0.0.1"),CoreTuningSettings())}
        reject {CoreTuningConfig.applyWorkerTransportDefaults(output("hysteria2"),CoreTuningSettings())}
        val reality=output();reality.getJSONObject("tls").put("reality",JSONObject().put("enabled",true))
        assertFalse(CoreTuningConfig.applyWorkerTransportDefaults(reality,CoreTuningSettings(insecure=true)).getJSONObject("tls").optBoolean("insecure"))
    }
}
