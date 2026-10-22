package org.mcrf.client

import java.lang.ref.WeakReference

/** Sampling is not connectivity supervision: failures still have their own fast path. */
internal object TelemetryCadence {
    @Volatile private var owner=WeakReference<Any>(null)
    private val listeners=java.util.concurrent.CopyOnWriteArraySet<()->Unit>()
    @Synchronized fun resumed(activity:Any){val wasVisible=uiVisible();owner=WeakReference(activity);if(!wasVisible)listeners.forEach {it()}}
    @Synchronized fun paused(activity:Any){if(owner.get()===activity){owner.clear();listeners.forEach {it()}}}
    fun uiVisible()=owner.get()!=null
    fun observe(listener:()->Unit){listeners.add(listener)}
    fun unobserve(listener:()->Unit){listeners.remove(listener)}
}
