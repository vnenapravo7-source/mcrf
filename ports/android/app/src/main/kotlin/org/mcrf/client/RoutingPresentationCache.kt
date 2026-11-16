package org.mcrf.client

import org.json.JSONObject

internal data class RoutingPresentation(val state:JSONObject,val counts:Map<String,Int>)

/** Only derived presentation is cached; encrypted storage remains authoritative. No Activity/views. */
internal class RoutingPresentationCache<T> {
    @Volatile private var entry:Pair<String,T>?=null
    fun get(revision:String):T?=entry?.takeIf {it.first==revision}?.second
    fun put(revision:String,value:T){entry=revision to value}
    fun clear(){entry=null}
}
