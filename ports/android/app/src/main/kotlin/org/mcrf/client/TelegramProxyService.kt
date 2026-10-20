package org.mcrf.client

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Own process: physical-network binding never changes VPN socket routing. No wake lock. */
class TelegramProxyService:Service() {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var network:ConnectivityManager
    private var monitor:ConnectivityManager.NetworkCallback?=null
    private val networks=linkedSetOf<Network>()
    private var selected:Network?=null
    private val clients=linkedSetOf<Messenger>()
    @Volatile private var generation=0L
    @Volatile private var wanted=false
    private var running=false
    private var status="Выключен"
    private var transport=""
    @Volatile private var child:java.lang.Process?=null
    private var engineUsage:EngineUpdateGate.Usage?=null
    private val prefs get()=getSharedPreferences("telegram-service",MODE_PRIVATE)
    private val messenger=Messenger(Handler(Looper.getMainLooper()) { message ->
        when(message.what) {TelegramBridge.SUBSCRIBE -> {message.replyTo?.let(clients::add);publish()};TelegramBridge.UNSUBSCRIBE -> clients.remove(message.replyTo)};true
    })
    override fun onCreate(){super.onCreate();network=getSystemService(ConnectivityManager::class.java)}
    override fun onBind(intent:Intent?)=messenger.binder
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val start=if(intent==null)prefs.getBoolean("wanted",false) else intent.action==TelegramBridge.START
        // Even STOP via startForegroundService fulfils the 5-second foreground contract.
        foreground(if(start)"Запускаем TG WS…" else "Останавливаем TG WS…")
        wanted=start;prefs.edit().putBoolean("wanted",start).apply()
        if(!start){generation++;running=false;status="Выключен";stopMonitor();publish();worker.execute {closeChild();main.post {if(!wanted){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}}};return START_NOT_STICKY}
        if(monitor==null)startMonitor()
        selectNetwork(true)
        return START_STICKY
    }
    private fun startMonitor() {
        network.allNetworks.filterTo(networks) {physical(it)}
        val callback=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value:Network){main.post{if(wanted){networks.add(value);selectNetwork()}}}
            override fun onCapabilitiesChanged(value:Network,caps:NetworkCapabilities){main.post{if(wanted){if(physical(value))networks.add(value) else networks.remove(value);selectNetwork()}}}
            override fun onLost(value:Network){main.post{if(wanted){networks.remove(value);selectNetwork()}}}
        }
        monitor=callback
        network.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback)
    }
    private fun physical(value:Network)=network.getNetworkCapabilities(value)?.let {it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true
    private fun selectNetwork(force:Boolean=false) {
        val next=network.activeNetwork?.takeIf {it in networks&&physical(it)} ?: networks.filter(::physical).maxByOrNull {value ->
            val caps=network.getNetworkCapabilities(value)!!
            (if(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))100 else 0)+(if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))10 else 0)
        }
        if(!force&&selected==next)return
        selected=next;val epoch=++generation;running=false;transport="";status=if(next==null)"Ожидание сети" else "Подключение…";publish();foreground(status)
        worker.execute {
            closeChild()
            if(!wanted||epoch!=generation)return@execute
            if(next==null){network.bindProcessToNetwork(null);return@execute}
            try {
                check(network.bindProcessToNetwork(next)) {"Не удалось выбрать сеть"}
                engineUsage=EngineUpdateGate.acquireUsage(this)?:throw IllegalStateException("Заверши установку движка")
                val binary=EngineModules.executable(this,"tgws")
                java.net.ServerSocket(TelegramBridge.DEFAULT_PORT,1,java.net.InetAddress.getByName("127.0.0.1")).use { }
                val builder=ProcessBuilder(binary.absolutePath,"-port",TelegramBridge.DEFAULT_PORT.toString()).directory(cacheDir).redirectErrorStream(true)
                // An exec'ed Go process needs explicit NDK socket/DNS binding; it
                // cannot rely on the Java process's libc binding surviving exec.
                builder.environment()["MCRF_NETWORK_HANDLE"]=next.networkHandle.toString()
                val process=builder.start();child=process
                Thread({try {process.inputStream.bufferedReader(Charsets.US_ASCII).useLines {lines ->lines.forEach {line ->
                    val nextTransport=when(line){"MCRF_TGWS_WS"->"WebSocket";"MCRF_TGWS_TCP"->"TCP · резерв";else->""}
                    if(nextTransport.isNotEmpty())main.post {if(wanted&&epoch==generation&&transport!=nextTransport){transport=nextTransport;publish();ClientEvents.record(this,"Telegram","Транспорт: $nextTransport")}}
                }}}catch(_:Exception){}},"mcrf-tg-output").apply {isDaemon=true;start()}
                val deadline=System.nanoTime()+5_000_000_000L
                while(wanted&&epoch==generation&&process.isAlive&&System.nanoTime()<deadline) {
                    if(ready()){main.post{if(wanted&&epoch==generation){running=true;status="127.0.0.1:${TelegramBridge.DEFAULT_PORT}";publish();foreground(status);ClientEvents.record(this,"Telegram","TG WS запущен")}};break}
                    Thread.sleep(120)
                }
                if(!wanted||epoch!=generation){closeChild();return@execute}
                check(ready()&&process.isAlive) {"Не удалось запустить TG WS. Возможно, порт занят"}
                Thread({val code=process.waitFor();try{worker.execute {if(child===process)closeChild()}}catch(_:java.util.concurrent.RejectedExecutionException){};main.post{if(wanted&&epoch==generation){running=false;status="TG WS остановился · включи заново";wanted=false;prefs.edit().putBoolean("wanted",false).apply();stopMonitor();publish();ClientEvents.record(this,"Ошибка", "TG WS завершился ($code)");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}},"mcrf-tg-watch").apply {isDaemon=true;start()}
            }catch(_:Exception){closeChild();main.post{if(epoch==generation){wanted=false;running=false;status="Не удалось запустить TG WS · проверь сеть и свободный порт";prefs.edit().putBoolean("wanted",false).apply();stopMonitor();publish();ClientEvents.record(this,"Ошибка",status);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}}
        }
    }
    private fun ready():Boolean=try {Socket().use {socket ->socket.connect(InetSocketAddress("127.0.0.1",TelegramBridge.DEFAULT_PORT),250);socket.soTimeout=500;socket.outputStream.write(byteArrayOf(5,1,0));socket.inputStream.read()==5&&socket.inputStream.read()==0}}catch(_:Exception){false}
    private fun closeChild(){
        val process=child
        if(process!=null){
            try{process.outputStream.write("STOP\n".toByteArray(Charsets.US_ASCII));process.outputStream.flush();process.outputStream.close();if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(1,TimeUnit.SECONDS))process.destroyForcibly()}}
            catch(_:Exception){process.destroyForcibly()}
            if(!process.waitFor(3,TimeUnit.SECONDS)||process.isAlive)throw IllegalStateException("TG WS ещё завершается; блокировка движка сохранена")
            child=null
        }
        engineUsage?.close();engineUsage=null
    }
    private fun stopMonitor(){monitor?.let {try{network.unregisterNetworkCallback(it)}catch(_:Exception){}};monitor=null;networks.clear();selected=null}
    private fun publish(){val data=Bundle().apply {putBoolean("wanted",wanted);putBoolean("running",running);putString("message",status);putString("transport",transport);putInt("port",TelegramBridge.DEFAULT_PORT)};val gone=clients.filter {client ->try{client.send(Message.obtain(null,TelegramBridge.STATE).apply {this.data=Bundle(data)});false}catch(_:RemoteException){true}};clients.removeAll(gone.toSet())}
    private fun foreground(message:String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("telegram","Telegram WS",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,20,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,21,Intent(this,TelegramProxyService::class.java).setAction(TelegramBridge.STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"telegram").setSmallIcon(R.drawable.status_icon).setContentTitle("MCRF · Telegram WS").setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null,"Остановить",stop).build()).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(20,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(20,notification)
    }
    override fun onDestroy(){generation++;wanted=false;stopMonitor();worker.execute{closeChild();network.bindProcessToNetwork(null)};worker.shutdown();super.onDestroy()}
}
