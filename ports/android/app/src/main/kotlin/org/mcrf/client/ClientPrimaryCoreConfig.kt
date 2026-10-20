package org.mcrf.client

import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import java.security.MessageDigest

/** Sing-box worker preparation only. Caller first verifies installed module/signature/ABI.
 * No profile conversion, preference writes, fallback, native host dependency or TUN creation. */
object ClientPrimaryCoreConfig {
    class Prepared internal constructor(val profile: String,val envelope: String,val capabilitySha256: String,
                                        val mode: Engine.Mode,val acquisition: Engine.TunAcquisition,val validation: Engine.Validation) {
        fun request(generation: Long,dialIpv4: String,stateDirectory: String,bootstrapIpv4: Array<String>): Engine.Request =
            Engine.Request(generation,-1,envelope,profile,dialIpv4,stateDirectory,0,bootstrapIpv4,mode,validation,acquisition,capabilitySha256)
        override fun toString()="PreparedSingBoxConfig(mode=$mode)" // never expose config/credentials
    }
    fun envelope(finalConfig: JSONObject,vpn: Boolean,metered: Boolean): String {
        val captured=JSONObject(finalConfig.toString())
        val inbounds=captured.optJSONArray("inbounds")
        val tunCount=(0 until (inbounds?.length() ?: 0)).count {inbounds!!.getJSONObject(it).optString("type")=="tun"}
        require(if(vpn)tunCount==1 else tunCount==0 && inbounds!=null && inbounds.length()>0) {"PRIMARY_CONFIG_TUN_MODE"}
        return JSONObject().put("schema",1).put("mode",if(vpn)"vpn" else "proxy").put("metered",metered).put("config",captured)
            .toString().also {require(it.toByteArray(Charsets.UTF_8).size<=4*1024*1024) {"PRIMARY_CONFIG_SIZE"}}
    }
    /** Runs the actual installed engine's full preflight; fixtures may only prove host wiring. */
    fun prepareSingBox(engine: Engine,profile: String,finalConfig: JSONObject,vpn: Boolean,metered: Boolean): Prepared {
        val payload=envelope(finalConfig,vpn,metered)
        return preflight(engine,profile,payload,vpn,false)
    }
    /** Explicit no-ingress probe, still native validated and fingerprint-bound PROXY/NONE. */
    fun prepareSingBoxProbe(engine: Engine,profile: String,finalConfig: JSONObject): Prepared {
        return preflight(engine,profile,probeEnvelope(finalConfig),false,true)
    }
    /** Main can build this without loading module runtime; worker validation remains mandatory. */
    fun probeEnvelope(finalConfig: JSONObject): String {
        val config=JSONObject(finalConfig.toString())
        require(config.optJSONArray("inbounds").let {it==null || it.length()==0} &&
            config.optJSONArray("services").let {it==null || it.length()==0} && !config.has("ntp") &&
            config.optJSONObject("experimental").let {it==null || !it.has("clash_api") && !it.has("v2ray_api") && !it.has("debug")}) {"PRIMARY_PROBE_LISTENER_FORBIDDEN"}
        val payload=JSONObject().put("schema",1).put("mode","probe").put("metered",false).put("config",config).toString()
        require(payload.toByteArray(Charsets.UTF_8).size<=4*1024*1024) {"PRIMARY_CONFIG_SIZE"}
        return payload
    }
    private fun preflight(engine: Engine,profile: String,payload: String,vpn: Boolean,probe: Boolean): Prepared {
        val raw=engine.capabilitiesJson()
        require(raw.toByteArray(Charsets.UTF_8).size<=65536) {"PRIMARY_CAPABILITIES_SIZE"}
        val caps=JSONObject(raw)
        require(caps.get("schema")==1 && caps.getString("kind")=="sing-box" && caps.get("spi")==1 && caps.get("coreApi")==1 &&
            caps.get("fullNativeConfig")==true && caps.getString("tunPlanMode")=="deferred-native-options" && caps.get("oneRunPerProcess")==true &&
            (vpn || caps.get("proxyOnly")==true) && (!probe || caps.get("isolatedProbe")==true)) {"PRIMARY_CAPABILITIES_MISMATCH"}
        val sha=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
        val mode=if(vpn)Engine.Mode.VPN else Engine.Mode.PROXY
        val acquisition=if(vpn)Engine.TunAcquisition.DEFERRED else Engine.TunAcquisition.NONE
        val validation=engine.validate(profile,payload,mode,acquisition)
        require(validation.supported && validation.mode==mode && validation.tunAcquisition==acquisition) {"PRIMARY_PROFILE_PREFLIGHT_FAILED"}
        val prepared=Prepared(profile,payload,sha,mode,acquisition,validation)
        // Execute the real shared constructor immediately to verify bound validation/capability
        // fingerprints before the host establishes anything. This object opens no resources.
        prepared.request(1,"","",emptyArray())
        return prepared
    }
}
