package org.mcrf.client

import java.util.concurrent.Executors

/** Settings disk writes never wait on the UI; one queue preserves edit order. */
object RoutingDefaultsWrites {
    private val queue = Executors.newSingleThreadExecutor { task -> Thread(task, "mcrf-routing-settings").apply { isDaemon = true } }
    fun submit(task: () -> Unit) { queue.execute(task) }
}
