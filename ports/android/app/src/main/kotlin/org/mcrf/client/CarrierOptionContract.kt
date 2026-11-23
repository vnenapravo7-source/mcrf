package org.mcrf.client

import org.json.JSONObject

/** Feature capability, not a guessed version/name of a separately signed APK. */
internal object CarrierOptionContract {
    const val SRTP_MODE="org.mcrf.engines.csqtt25_srtp_mode"
    fun missing(profile:JSONObject,capabilityVersion:(String)->Int):Boolean =
        requiredAll(profile).any {capabilityVersion(it)<1}
    fun requiredAll(profile:JSONObject):Set<String> = buildSet {
        required(profile)?.let(::add)
        if(Csqtt25Profile.isProfile(profile)){CsqttGooby.validate(profile);if(CsqttGooby.active(profile))add(CsqttGooby.CAPABILITY)}
        // Old wrappers may ignore unknown JSON. Never silently replace an explicit
        // non-default mode with AUTO, regardless of APK version/name.
        if(Csqtt25Profile.isProfile(profile)&&Csqtt25Profile.srtpMode(profile)!="auto")add(SRTP_MODE)
        if(CsqttSwgVersions.needsNewRuntime(profile))add(CsqttSwgVersions.CAPABILITY)
        if(profile.optString("type") in SwgProfile.TYPES) {
            val hashes=profile.opt("hashes")
            val count=if(hashes is org.json.JSONArray)hashes.length() else profile.optString("hashes").split(Regex("[,;\\s]+" )).count {it.isNotBlank()}
            if(count>6)add("org.mcrf.engines.turn_hash_pool")
            // The global empty-rule exit can carry IPv6 even when the server
            // name resolved to IPv4. Old SOCKS modules must not accept silently.
            if(profile.optString("type")=="wdtt")add("org.mcrf.engines.wdtt_dual_family")
        }
    }
    fun required(profile:JSONObject):String?=when {
        Csqtt25Profile.isProfile(profile)->"org.mcrf.engines.csqtt25_swg_panel"
        profile.optString("type")=="csqtt"&&CsqttGenerations.family(profile).engine in setOf("csqtt20","csqtt215")&&profile.optString("fingerprint","chrome")!="chrome"->"org.mcrf.engines.csqtt_historical_options"
        profile.optString("type")=="wdtt"&&profile.optBoolean("rt_masque")->"org.mcrf.engines.wdtt_masque"
        profile.optString("type")=="qwdtt"&&profile.optString("fingerprint","chrome")!="chrome"->"org.mcrf.engines.qwdtt_fingerprint"
        profile.optString("type")=="csqtt"&&(profile.optString("vk_anon_path")=="auto_js"||profile.optString("fingerprint","chrome")!="chrome")->"org.mcrf.engines.csqtt_account_options"
        profile.optString("type")=="wdtt"&&(!profile.optBoolean("vkcalls_preflight",true)||profile.optBoolean("turn_stream_first")||profile.optString("turn_sni").isNotEmpty()||profile.optString("client_ids").isNotEmpty()||profile.optString("custom_vk_client_id").isNotEmpty())->"org.mcrf.engines.wdtt_provider_options"
        else->null
    }
}
