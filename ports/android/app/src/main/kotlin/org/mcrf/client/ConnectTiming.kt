package org.mcrf.client

import android.content.Context
import android.os.SystemClock

/** Monotonic local durations only: never profile, host, settings or native diagnostics. */
object ConnectTiming {
    const val EXTRA="mcrf_connect_started_ns"
    fun now()=SystemClock.elapsedRealtimeNanos()
    fun mark(context:Context,phase:String,started:Long){
        require(phase.matches(Regex("[a-z0-9_.-]{1,48}")))
        val end=now();if(started<=0||started>end)return
        ClientEvents.record(context,"Тайминги","$phase=${(end-started)/1_000_000}ms mono=${end/1_000_000} pid=${android.os.Process.myPid()}")
    }
    inline fun <T> measure(context:Context,phase:String,action:()->T):T {
        val start=now();try{return action()}finally{mark(context,phase,start)}
    }
}
