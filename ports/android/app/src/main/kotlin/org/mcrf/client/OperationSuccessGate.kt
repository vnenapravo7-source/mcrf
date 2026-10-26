package org.mcrf.client

import java.util.concurrent.atomic.AtomicBoolean

/** Completion is also called for rejected/busy work; only a returned operation is success. */
internal class OperationSuccessGate {
    private val succeeded = AtomicBoolean(false)

    fun <T> execute(action: () -> T): T {
        val result = action()
        succeeded.set(true)
        return result
    }

    fun complete(onSuccess: () -> Unit, always: () -> Unit = {}) {
        try {
            if (succeeded.compareAndSet(true, false)) onSuccess()
        } finally {
            always()
        }
    }
}
