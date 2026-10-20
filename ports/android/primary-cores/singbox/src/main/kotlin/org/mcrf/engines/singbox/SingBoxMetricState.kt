package org.mcrf.engines.singbox

import org.mcrf.engine.spi.v1.Engine
import java.util.concurrent.atomic.AtomicReference

/** Immutable actual StatusMessage TX/RX snapshot; blocked attempts have no native source. */
class SingBoxMetricState {
    private fun empty()=Engine.Stats(Engine.Metric.unavailable(),Engine.Metric.unavailable(),Engine.Metric.unavailable())
    private val value=AtomicReference(empty())
    fun observed(tx: Long,rx: Long) {value.set(Engine.Stats(Engine.Metric.of(tx),Engine.Metric.of(rx),Engine.Metric.unavailable()))}
    fun snapshot(): Engine.Stats = value.get()
    fun clear() {value.set(empty())}
}
