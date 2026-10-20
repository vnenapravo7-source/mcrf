package org.mcrf.client

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Human-authorized timing diagnostics only: existing account, no registration or profile commit. */
object WarpTimingDeviceChecks {
    @JvmStatic fun run(test:Instrumentation,passed:Consumer<String>){
        val context=test.targetContext
        val before=ProfileStore.snapshot(context)
        val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        check(WarpSettingsStore.load(context).termsAccepted){"Existing WARP consent required"}
        val catalogue=Connections(context).apply {load()}
        val account=WarpRegistrationStore.load(context)?:catalogue.entries().firstOrNull(WarpProfile::isWarp)?.let {catalogue.warpAccount(it.id)}
        check(account!=null){"Existing account required; diagnostic never registers"}
        data class Case(val name:String,val country:String,val protocol:String="auto",val excludeDme:Boolean=true,val extended:Boolean=false)
        val cases=listOf(
            Case("RU-auto-noDME","RU"),Case("RU-auto-allowDME","RU",excludeDme=false),
            Case("RU-WG-noDME","RU","wg"),Case("RU-AWG-noDME","RU","awg"),
            Case("foreign-auto","foreign"),Case("foreign-WG","foreign","wg"),Case("foreign-AWG","foreign","awg"),
            Case("any-auto-internal","any"),Case("RU-extended","RU",extended=true),Case("foreign-extended","foreign",extended=true))
        fun notice(value:String){test.sendStatus(0,Bundle().apply {putString("stream","WARP_TIMING $value\n")})}
        val trace=WarpTraceProbe(context)
        try {
            cases.forEachIndexed {index,case->
                check(!Thread.currentThread().isInterrupted)
                val started=System.nanoTime();val limit=if(case.extended)90L else 60L
                val deadline=started+TimeUnit.SECONDS.toNanos(limit)
                val cancelled={Thread.currentThread().isInterrupted||System.nanoTime()>=deadline}
                var nativeMillis=0L;var traceMillis=0L;var nativeCalls=0;var traceCalls=0;var lastProgress=0L
                notice("case=${index+1}/${cases.size} name=${case.name} budget_seconds=$limit begin")
                val scanner=WarpScanner(context,cancelled){phase,done,total->
                    if(System.nanoTime()-lastProgress>=TimeUnit.SECONDS.toNanos(10)){
                        lastProgress=System.nanoTime();notice("name=${case.name} phase=$phase done=$done total=$total")
                    }
                }
                val backend=WarpOperations.Backend {request->
                    val time=System.nanoTime();nativeCalls++
                    try {scanner.execute(request)}finally{nativeMillis+=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-time)}
                }
                var outcome="not_completed"
                try {
                    val verified=WarpOperations.scan(requireNotNull(account),backend,{config->
                        val time=System.nanoTime();traceCalls++
                        try {trace.fetch(config,cancelled)}finally{traceMillis+=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-time)}
                    },WarpOperations.Options(case.protocol,case.country,case.excludeDme,case.extended),cancelled)
                    outcome="verified_${verified.trace.country}_${verified.trace.node}"
                }catch(error:Exception){
                    if(Thread.currentThread().isInterrupted)throw error
                    outcome=if(System.nanoTime()>=deadline)"diagnostic_budget_exhausted"else when(error){
                        is WarpOperations.ScanFailure->"scan_rejected"
                        is java.util.concurrent.CancellationException->"cancelled"
                        else->"${error.javaClass.simpleName}"
                    }
                }
                notice("name=${case.name} outcome=$outcome wall_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)} native_ms=$nativeMillis native_calls=$nativeCalls trace_ms=$traceMillis trace_calls=$traceCalls")
                check(ProfileStore.snapshot(context)==before&&context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences){"Diagnostic changed protected user state"}
            }
        }finally{check(ProfileStore.snapshot(context)==before&&context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences)}
        passed.accept("Timing matrix completed without registration, account/catalogue/settings changes; per-case outcomes are diagnostics, not blanket traffic PASS")
    }
}
