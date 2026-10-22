package org.mcrf.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** One counter RPC at a time. No screen-off ticks, no global WebView timer changes. */
internal class NotificationTrafficSampler(private val context:Context,private val main:Handler,private val worker:ExecutorService,
    private val active:()->Boolean,private val sample:()->Unit,private val publish:()->Unit):AutoCloseable {
    private val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
    private val power=context.getSystemService(PowerManager::class.java)
    @Volatile private var interactive=power?.isInteractive?:true
    private val revision=AtomicLong()
    @Volatile private var closed=false
    private var busy=false
    private var previous=Long.MIN_VALUE
    private var registered=false
    private val tick=Runnable {poll()}
    private fun interval()=NotificationTrafficSettings.load(prefs).interval(TelemetryCadence.uiVisible(),interactive)
    private val preferenceListener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key in NotificationTrafficSettings.keys)main.post {arm(force=true)}
    }
    // A wake and Activity.onResume may arrive together. Recalculate the due
    // time on presence changes; only wake explicitly forces a fresh sample.
    private val presence:()->Unit={main.post {arm()};Unit}
    private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
        interactive=intent.action==Intent.ACTION_SCREEN_ON
        arm(force=interactive)
    }}
    init {
        try {
            val filter=IntentFilter(Intent.ACTION_SCREEN_ON).apply {addAction(Intent.ACTION_SCREEN_OFF)}
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver,filter)
            registered=true
            interactive=power?.isInteractive?:true
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener);TelemetryCadence.observe(presence)
            main.post {arm(force=true)}
        }catch(error:Exception){close();throw error}
    }
    private fun arm(force:Boolean=false){
        main.removeCallbacks(tick);revision.incrementAndGet()
        if(closed||!active())return
        publish()
        val cadence=interval()?:return
        if(force)previous=Long.MIN_VALUE
        if(!busy){val now=SystemClock.elapsedRealtime();main.postDelayed(tick,if(previous==Long.MIN_VALUE||now<previous)0L else (cadence-(now-previous)).coerceAtLeast(0))}
    }
    private fun poll(){
        if(closed||busy||!active()||interval()==null)return
        busy=true;previous=SystemClock.elapsedRealtime();val stamp=revision.get()
        try{worker.execute {
            try{if(!closed&&revision.get()==stamp&&active()&&interval()!=null)sample()}
            catch(_:Exception){/* The independent service supervisor owns error reporting. */}
            finally{main.post {busy=false;if(!closed)arm()}}
        }}catch(_:RejectedExecutionException){busy=false;close()}
    }
    override fun close(){
        if(closed)return
        closed=true;revision.incrementAndGet();main.removeCallbacks(tick)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);TelemetryCadence.unobserve(presence)
        if(registered){runCatching {context.unregisterReceiver(receiver)};registered=false}
    }
}
