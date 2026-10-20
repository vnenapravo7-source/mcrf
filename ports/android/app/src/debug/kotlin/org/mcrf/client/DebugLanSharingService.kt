package org.mcrf.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Debug APK only. One short-lived authenticated LAN listener; never obtains TUN or changes preferences. */
class DebugLanSharingService:ClientPrimaryHostPlatform(){
    private val executor=Executors.newSingleThreadExecutor()
    private val occupied=AtomicBoolean()
    @Volatile private var stopping=false
    @Volatile private var core:ClientPrimaryWorker?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val name=intent?.getStringExtra("fixture").orEmpty()
        @Suppress("DEPRECATION") val reply=intent?.getParcelableExtra<ResultReceiver>("reply")
        if(!name.matches(Regex("mcrf-lan-[0-9a-f-]{36}\\.json"))||!occupied.compareAndSet(false,true)){stopSelf(startId);return START_NOT_STICKY}
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("lan-fixture","Проверка раздачи прокси",NotificationManager.IMPORTANCE_LOW))
        val notice=Notification.Builder(this,"lan-fixture").setSmallIcon(R.drawable.status_icon).setContentTitle("MCRF · проверка локального прокси").setContentText("Временный порт с паролем · до 90 секунд").build()
        if(Build.VERSION.SDK_INT>=34)startForeground(29,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(29,notice)
        executor.execute {
            var success=false
            var failure=""
            try{
                val input=File(cacheDir,name);require(input.isFile&&input.length()<=8192&&!java.nio.file.Files.isSymbolicLink(input.toPath()))
                val data=JSONObject(input.readText());val port=data.getInt("port")
                val auth=JSONObject().put("enabled",true).put("username",data.getString("username")).put("password",data.getString("password"))
                val original=if(data.has("candidate"))WarpProbePlan.configuration(data.getString("candidate"),port,auth.getString("username"),auth.getString("password")).put("inbounds",JSONArray())
                    else JSONObject().put("inbounds",JSONArray()).put("outbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","fixture-direct"))).put("route",JSONObject().put("final","fixture-direct"))
                val dnsFixture=data.optJSONObject("dnsState")
                val generated=if(dnsFixture!=null)RouteRules.apply(original,dnsFixture,false)else original
                val config=ClientTunConfig.apply(generated,ClientTunSettings(mixedEnabled=false,shareLan=dnsFixture==null,lanPort=port,bypassPrivate=true,dnsPort=if(dnsFixture!=null)data.getInt("dnsPort")else 0),auth,port).config
                val generation=System.nanoTime().coerceAtLeast(1)
                val platform=forRun(generation,{stopping},tun={error("LAN fixture must never obtain TUN")},traffic={_,_->})
                val worker=try{ClientPrimaryWorker.connect(applicationContext,"singbox",generation,platform,ClientPrimaryWarpWorkerService::class.java).also {core=it}}
                    catch(pending:ClientPrimaryWorker.CleanupPending){core=pending.worker;throw pending}
                val envelope=ClientPrimarySelection.envelope(config,false,false)
                require(worker.validate("",envelope,Engine.Mode.PROXY).supported)
                worker.start("",envelope,Engine.Mode.PROXY)
                reply?.send(1,Bundle())
                val until=System.nanoTime()+90_000_000_000L
                val stop=File(cacheDir,"$name.stop")
                while(!stop.exists()&&!stopping&&System.nanoTime()<until)Thread.sleep(100)
                success=stop.exists()&&!stopping
            }catch(error:Exception){success=false;failure=error.javaClass.simpleName+": "+error.message.orEmpty().take(256)}
            finally{
                stopping=true;val owned=core
                val retirement=ClientPrimaryRetirement(owned,requestStop={owned?.requestStop()},retireCallbacks={closePlatform(keepTun=true)},closeOwner={it.close()},dependents=listOf({closePlatform()}),completed={core=null})
                while(!retirement.attempt())Thread.sleep(retirement.retryDelayMillis())
                reply?.send(if(success)0 else 2,Bundle().apply {putString("failure",failure)})
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy(){stopping=true;core?.requestStop();executor.shutdown();super.onDestroy()}
}
