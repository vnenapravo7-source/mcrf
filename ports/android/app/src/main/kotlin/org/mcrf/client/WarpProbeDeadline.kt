package org.mcrf.client

/** A failed endpoint is retryable; an explicit user cancellation is not. */
object WarpProbeDeadline {
    fun check(cancelled:Boolean,expired:Boolean) {
        if(cancelled)throw java.util.concurrent.CancellationException("Проверка WARP отменена.")
        if(expired)throw java.util.concurrent.TimeoutException("Время проверки выхода WARP истекло.")
    }
}
