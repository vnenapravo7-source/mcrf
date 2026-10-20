package org.mcrf.client

/** One Activity-local rendered screen. Never static, never shared with encrypted storage. UI thread only. */
internal class RoutingScreenCache<T> {
    private data class Entry<T>(val owner:Any,val revision:String,val dark:Boolean,val screen:T)
    private var entry:Entry<T>?=null
    fun get(owner:Any,revision:String,dark:Boolean):T?=entry?.takeIf {it.owner===owner&&it.revision==revision&&it.dark==dark}?.screen
    fun put(owner:Any,revision:String,dark:Boolean,screen:T){entry=Entry(owner,revision,dark,screen)}
    fun clear(){entry=null}
}
