package org.mcrf.client

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Android can deliver a final DNS FD callback after its service's worker shuts down. */
class DrainingCallbackExecutor(private val worker:Executor):Executor {
    override fun execute(command:Runnable){try{worker.execute(command)}catch(_:RejectedExecutionException){command.run()}}
}
