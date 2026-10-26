package org.mcrf.client

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Opt-in only; one bounded check at a time, scoped to an unchanged live session. */
class BsDetectionMonitor(context:Context,private val snapshot:()->Stamp?,private val switch:(Stamp)->Unit,
    private val youtube:(Stamp,String)->DirectAccessCheck.Outcome):AutoCloseable {
    data class Stamp(val epoch:Long,val generation:Long,val vpnSection:Boolean,val tun:Boolean)
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor()
    @Volatile private var closed=false
    private var busy=false
    private fun sites()=BsDetectionSites.load {key,fallback->prefs.getString(key,fallback)?:fallback}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==KEY||key==BsDetectionInterval.KEY||key in BsDetectionSites.keys)schedule()}
    private val tick=object:Runnable {override fun run(){
        if(closed||!prefs.getBoolean(KEY,false))return
        main.postDelayed(this,interval())
        val stamp=snapshot()?:return
        if(busy)return
        busy=true
        val capturedSites=sites()
        executor.execute {
            val result=try{
                val direct=DirectAccessCheck.check(app,cancelled={closed||!prefs.getBoolean(KEY,false)||sites()!=capturedSites},sites=capturedSites)
                if(!closed&&prefs.getBoolean(KEY,false)&&stamp.vpnSection&&stamp.tun&&direct.directComplete)
                    direct.copy(youtube=youtube(stamp,capturedSites[2])) else direct
            }catch(_:Exception){null}
            main.post {
                busy=false
                if(!closed&&prefs.getBoolean(KEY,false)&&snapshot()==stamp&&sites()==capturedSites&&
                    (result?.shouldSwitch(true,true,stamp.vpnSection)==true||result?.shouldReturn(true,stamp.vpnSection)==true))switch(stamp)
            }
        }
    }}
    init {prefs.registerOnSharedPreferenceChangeListener(listener);schedule()}
    private fun interval()=BsDetectionInterval.millis(prefs.getInt(BsDetectionInterval.KEY,BsDetectionInterval.DEFAULT))
    private fun schedule(){main.removeCallbacks(tick);if(!closed&&prefs.getBoolean(KEY,false))main.postDelayed(tick,interval())}
    override fun close(){closed=true;prefs.unregisterOnSharedPreferenceChangeListener(listener);main.removeCallbacksAndMessages(null);executor.shutdownNow()}
    companion object {const val KEY="autoDetectBs"}
}
