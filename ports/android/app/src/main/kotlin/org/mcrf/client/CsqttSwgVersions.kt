package org.mcrf.client

import org.json.JSONObject

/** Explicit implementation selects native bytes; link v=2 never selects a release. */
internal object CsqttSwgVersions {
    const val CAPABILITY="org.mcrf.engines.csqtt25_swg_252_253"
    const val LIBRARY="libcsqtt252_transport.so"
    val revisions=mapOf(
        "swg-panel-2.5.2" to "1f51f962288d6e2286790e0770847a800745046e",
        "swg-panel-2.5.3" to "0188606a243c92a54440be09dc59a93848c463aa")
    fun needsNewRuntime(profile:JSONObject)=Csqtt25Profile.isProfile(profile)&&CsqttGenerations.implementation(profile) in revisions
    fun nativeImplementation(profile:JSONObject)=if(needsNewRuntime(profile))CsqttGenerations.implementation(profile)else "swg-panel-2.5.1"
}
