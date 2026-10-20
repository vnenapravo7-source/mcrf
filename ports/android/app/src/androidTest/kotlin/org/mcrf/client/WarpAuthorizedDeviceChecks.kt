package org.mcrf.client

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Reuses an account; a separate explicit human-authorized flag permits one registration, never a retry. */
object WarpAuthorizedDeviceChecks {
    @JvmStatic @JvmOverloads fun run(test:Instrumentation,passed:Consumer<String>,allowRegistration:Boolean=false) {
        val context=test.targetContext
        var baseline=ProfileStore.snapshot(context)
        val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        check(WarpSettingsStore.load(context).termsAccepted){"WARP consent missing; no registration or search performed"}
        val catalogue=Connections(context).apply {load()}
        var account=WarpRegistrationStore.load(context) ?: catalogue.entries().firstOrNull(WarpProfile::isWarp)?.let {catalogue.warpAccount(it.id)}
        check(account!=null||allowRegistration){"Existing WARP account unavailable; no registration performed"}
        val deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(18)
        val cancelled={Thread.currentThread().isInterrupted||System.nanoTime()>=deadline}
        var attempts=0
        var lastNotice=0L
        fun notice(value:String) {test.sendStatus(0,Bundle().apply {putString("stream","WARP: $value\n")})}
        val scanner=WarpScanner(context,cancelled){phase,done,total ->
            if(System.nanoTime()-lastNotice>TimeUnit.SECONDS.toNanos(15)) {
                lastNotice=System.nanoTime();notice("attempt $attempts/3 · $phase · $done/$total")
            }
        }
        val trace=WarpTraceProbe(context)
        try {
            if(account==null){
                notice("Single human-authorized registration; no automatic retry")
                account=WarpOperations.register(scanner,cancelled=cancelled)
                WarpRegistrationStore.save(context,requireNotNull(account))
                baseline=ProfileStore.snapshot(context)
                passed.accept("One human-authorized WARP registration saved encrypted for reuse; no subscription or server catalogue change")
            }
            val verified=WarpOperations.search(requireNotNull(account),scanner,{trace.fetch(it,cancelled)},WarpOperations.Options(protocol="auto",country="foreign",excludeDme=true),cancelled){n,total ->attempts=n;notice("attempt $n/$total")}
            check(verified.trace.country!="RU"&&verified.trace.node!="DME")
            passed.accept("Actual existing-account WARP native scan + app-core HTTPS trace confirms foreign exit ${verified.trace.country}/${verified.trace.node}; $attempts attempts, no catalogue commit or registration")
        } catch(failure:WarpOperations.SearchExhausted) {
            error("Actual WARP search exhausted3; ${failure.detail}; account/profile unchanged")
        } finally {
            check(ProfileStore.snapshot(context)==baseline && context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==prefs){"WARP fixture changed protected user state"}
        }
        passed.accept("After any explicitly authorized registration, WARP account, catalogue, settings and selection unchanged")
    }
}
