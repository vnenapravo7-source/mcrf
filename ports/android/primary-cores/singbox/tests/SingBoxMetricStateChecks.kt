package org.mcrf.engines.singbox
import org.mcrf.engine.spi.v1.Engine
object SingBoxMetricStateChecks {
    @JvmStatic fun main(args:Array<String>) {
        var checks=0
        fun verify(value:Boolean){check(value);checks++}
        val state=SingBoxMetricState();verify(!state.snapshot().anyAvailable())
        state.observed(0,0);val zero=state.snapshot()
        verify(zero.txPayload.value==0L && zero.rxPayload.value==0L && zero.blockedAttempts.value==null)
        verify(Engine.ControlReply.forStats(zero).code=="STATS_PARTIAL")
        verify(Engine.ControlReply.forStats(zero).counters==null)
        state.observed(123,456);verify(state.snapshot().txPayload.value==123L && state.snapshot().rxPayload.value==456L)
        verify(zero.txPayload.value==0L)
        try {state.observed(-1,5);error("Negative native metric accepted")}catch(_:IllegalArgumentException){checks++}
        verify(state.snapshot().txPayload.value==123L)
        state.clear();verify(!state.snapshot().anyAvailable())
        println("PASS $checks actual metric-state checks; no native status source exercised")
    }
}
