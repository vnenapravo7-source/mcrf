package org.mcrf.engines.singbox
import java.util.concurrent.atomic.AtomicBoolean
/** Exactly one terminal native DNS reply/error/cancel, including concurrent completion. */
class SingBoxDnsCompletion {
    private val done=AtomicBoolean()
    val active: Boolean get()=!done.get()
    fun finish(terminal: ()->Unit) {if(done.compareAndSet(false,true))terminal()}
}
