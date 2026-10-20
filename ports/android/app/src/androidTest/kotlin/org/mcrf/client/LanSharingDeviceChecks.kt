package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.io.File
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Real production LAN configuration + external sing-box. Host verifies both proxy protocols. */
object LanSharingDeviceChecks {
    @JvmStatic fun run(test:Instrumentation,name:String,passed:Consumer<String>){
        require(BuildConfig.DEBUG&&name.matches(Regex("mcrf-lan-[0-9a-f-]{36}\\.json")))
        val context=test.targetContext;val before=ProfileStore.snapshot(context)
        val input=File(context.cacheDir,name);val stop=File(context.cacheDir,"$name.stop")
        require(input.isFile&&input.length()<=8192&&!java.nio.file.Files.isSymbolicLink(input.toPath()))
        val data=JSONObject(input.readText());val address=LanSharingAddresses.discover().firstOrNull()?:error("No LAN IPv4 address")
        if(data.optBoolean("warp")){
            val account=WarpRegistrationStore.load(context)?:error("Existing WARP account required; LAN test never registers")
            val until=System.nanoTime()+18*60_000_000_000L
            val cancelled={System.nanoTime()>=until||stop.exists()}
            val scanner=WarpScanner(context,cancelled)
            val trace=WarpTraceProbe(context)
            val verified=WarpOperations.search(account,scanner,{trace.fetch(it,cancelled)},cancelled=cancelled){n,total->test.sendStatus(0,Bundle().apply {putString("stream","LAN_PROGRESS WARP attempt $n/$total\n")})}
            data.put("candidate",verified.server.original)
        }
        val nonce=java.util.UUID.randomUUID().toString()
        val origin=ServerSocket(0,8,InetAddress.getByName(address))
        val originThread=Thread({while(!origin.isClosed)try{origin.accept().use {socket->
            socket.soTimeout=3000;val reader=socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val first=reader.readLine();var size=first?.length?:0
            while(true){val line=reader.readLine()?:break;size+=line.length;require(size<=8192);if(line.isEmpty())break}
            val body=if(first?.contains("/$nonce ")==true)nonce else "invalid-fixture-path"
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(Charsets.US_ASCII))
        }}catch(_:Exception){}},"mcrf-lan-fixture-origin").apply {isDaemon=true;start()}
        val port=ServerSocket(0).use {it.localPort};require(port>=1024)
        input.writeText(data.put("port",port).toString());android.system.Os.chmod(input.absolutePath,0x180)
        val ready=CountDownLatch(1);val completed=CountDownLatch(1);var result=2
        val receiver=object:ResultReceiver(Handler(Looper.getMainLooper())){override fun onReceiveResult(code:Int,bundle:Bundle?){if(code==1)ready.countDown()else{result=code;completed.countDown()}}}
        // The service process cannot load anonymous classes from the instrumentation
        // APK. Send the framework ResultReceiver parcel wrapper, retaining its Binder.
        val parcel=Parcel.obtain()
        val remoteReceiver=try{receiver.writeToParcel(parcel,0);parcel.setDataPosition(0);ResultReceiver.CREATOR.createFromParcel(parcel)}finally{parcel.recycle()}
        try{
            context.startForegroundService(Intent().setClassName(context.packageName,"${context.packageName}.DebugLanSharingService").putExtra("fixture",name).putExtra("reply",remoteReceiver))
            check(ready.await(20,TimeUnit.SECONDS)){"LAN fixture startup failed"}
            test.sendStatus(0,Bundle().apply {putString("stream","LAN_READY "+JSONObject().put("address",address).put("port",port).put("origin",origin.localPort).put("nonce",nonce).put("warp",data.optBoolean("warp")).toString()+"\n")})
            check(completed.await(100,TimeUnit.SECONDS)&&result==0){"LAN fixture did not acknowledge clean owned stop"}
            passed.accept("Production authenticated LAN listener started and retired through installed sing-box; no TUN acquired")
        }finally{
            if(completed.count!=0L){stop.createNewFile();completed.await(15,TimeUnit.SECONDS)}
            origin.close();originThread.join(4000)
            if(completed.count==0L){input.delete();stop.delete()}
            check(completed.count==0L){"LAN owned cleanup still pending"}
            check(ProfileStore.snapshot(context)==before){"LAN fixture changed real user data"}
        }
        passed.accept("Encrypted profiles, settings, WARP account and catalogue unchanged")
    }
}
