package org.mcrf.client

import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread job ownership. Stopped/replaced callbacks can never finish a newer run. */
internal class BehaviorScheduleJobRuns<P> {
    class Run<P>(val params:P,val cancelled:AtomicBoolean=AtomicBoolean())
    private val runs=mutableMapOf<Int,Run<P>>()
    fun start(id:Int,params:P):Run<P> {
        val run=Run(params);runs.put(id,run)?.cancelled?.set(true);return run
    }
    fun stop(id:Int):Boolean= runs.remove(id)?.let {it.cancelled.set(true);true}?:false
    fun finish(id:Int,expected:Run<P>):Boolean {
        if(runs[id]!==expected||expected.cancelled.get())return false
        runs.remove(id);return true
    }
    fun destroy():Set<Int> {
        val ids=runs.keys.toSet();runs.values.forEach {it.cancelled.set(true)};runs.clear();return ids
    }
}
