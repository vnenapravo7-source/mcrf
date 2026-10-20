package org.mcrf.client

import org.json.JSONObject

/** Scoped updates prevent a stale page from overwriting another page's preferences. */
object OptionalCorePages {
    private val xray=setOf("xrayVlessDefault","xrayMuxDefault","xrayMuxConcurrency")
    private val tls=setOf("customFragmentDefault","fragmentSize","fragmentSleep","mixedCaseSniDefault")
    fun keys(page:String):Set<String> = when(page){"xray"->xray;"tls"->tls;"all"->xray+tls;else->throw IllegalArgumentException("Unknown optional core page")}
    fun merge(current:OptionalCoreSettings,edited:JSONObject,keys:Set<String>):OptionalCoreSettings {
        require(keys.all {it in xray+tls})
        val merged=current.json();keys.forEach {merged.put(it,edited.get(it))}
        return OptionalCoreSettings.fromJson(merged)
    }
}
