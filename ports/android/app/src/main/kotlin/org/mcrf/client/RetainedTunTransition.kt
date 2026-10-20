package org.mcrf.client

import java.io.IOException

/** No gap in capture: publish the replacement before retiring the old descriptor. */
internal object RetainedTunTransition {
    fun <T> replace(old:T, live:()->Boolean, establish:()->T, publish:(T)->Unit, close:(T)->Unit) {
        if(!live())throw IOException("PLATFORM_RETIRED")
        val next=establish()
        if(!live()) {
            close(next)
            throw IOException("PLATFORM_RETIRED")
        }
        try { publish(next) } catch(error:Exception) { close(next);throw error }
        close(old)
    }
}
