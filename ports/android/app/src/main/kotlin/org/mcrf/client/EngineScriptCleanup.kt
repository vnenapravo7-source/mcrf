package org.mcrf.client

/** The exact child stays owned until retirement succeeds. Later cleanup failures
 * must not skip releasing the mutation lease, private file or UI busy state. */
internal object EngineScriptCleanup {
    fun finish(child: AutoCloseable?, complete: () -> Unit, release: () -> Unit,
               deleteRequest: () -> Unit, finished: () -> Unit) {
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() }
            catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
            }
        }
        try {
            child?.let { OptionalCoreRetirement.drain(it) }
            complete()
        } catch (error: Throwable) {
            failure = error
        } finally {
            cleanup(release)
            cleanup(deleteRequest)
            cleanup(finished)
        }
        failure?.let { throw it }
    }
}
