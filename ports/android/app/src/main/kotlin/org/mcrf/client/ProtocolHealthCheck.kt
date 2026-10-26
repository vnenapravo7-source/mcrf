package org.mcrf.client

/** Retain the owned transport's safe cause when outer HTTPS/DNS throws. Trust
 * denial must stay a SecurityException, cancellation must not become I/O. */
internal object ProtocolHealthCheck {
    fun verify(probe:()->Boolean,detail:()->Exception):Boolean {
        val healthy=try{probe()}catch(error:Exception){
            if(error is InterruptedException||error is java.util.concurrent.CancellationException)throw error
            val native=detail()
            val message=native.message.orEmpty()+"\nПроверка передачи: ${error.javaClass.simpleName}: "+ClientEvents.redact(error.message).take(600)
            throw if(native is SecurityException||error is SecurityException)SecurityException(message,error)
                else java.io.IOException(message,error)
        }
        if(!healthy)throw detail()
        return true
    }
}
