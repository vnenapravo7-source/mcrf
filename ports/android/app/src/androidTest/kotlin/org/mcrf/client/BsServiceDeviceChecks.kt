package org.mcrf.client

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/** Exercises real service fallback using only private local encrypted OpenFlux exits. */
object BsServiceDeviceChecks {
 @JvmStatic fun run(context:Context,temporary:File,passed:Consumer<String>){
  fun verify(ok:Boolean,message:String){check(ok){message};passed.accept(message)}
  fun await(seconds:Int,predicate:()->Boolean):Boolean {val end=System.nanoTime()+seconds*1_000_000_000L;while(System.nanoTime()<end){if(predicate())return true;Thread.sleep(100)};return predicate()}
  require(!McrfVpnService.status.startsWith("VPN подключён")&&!McrfVpnService.status.startsWith("Прокси ")){"Do not interrupt an active user connection"}
  val before=ProfileStore.snapshot(context);val preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
  val binary=File(NativeCarrier.executable(context,"openflux").parentFile,"libopenflux_testserver.so")
  val address=NetworkInterface.getNetworkInterfaces().toList().filter {it.isUp&&!it.isLoopback&&!it.name.startsWith("tun")}.flatMap {it.inetAddresses.toList()}.filterIsInstance<Inet4Address>().first { !it.isLoopbackAddress&&!it.isLinkLocalAddress }
  val workers=Executors.newCachedThreadPool();val running=AtomicBoolean(true);val requests=AtomicInteger()
  val path="/mcrf-bs-${UUID.randomUUID()}"
  val exits=mutableListOf<Process>()
  ServerSocket(0,8,address).use { origin ->
   origin.soTimeout=1000
   workers.submit {while(running.get()){val socket=try{origin.accept()}catch(_:SocketTimeoutException){continue}catch(_:Exception){break};workers.submit { socket.use {
    try {it.soTimeout=5000;val request=java.io.ByteArrayOutputStream();while(request.size()<8192){val n=it.getInputStream().read();if(n<0)break;request.write(n);if(request.toString("US-ASCII").endsWith("\r\n\r\n"))break}
      check(request.toString("US-ASCII").contains(" $path "));requests.incrementAndGet()
      it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n"+"B".repeat(4096)).toByteArray(Charsets.US_ASCII))
    }catch(_:Exception){}
   } }}}
   fun exit(name:String):ServerImport.Server {
    val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {it.localPort};val secret=UUID.randomUUID().toString()+UUID.randomUUID().toString()
    val config=File(temporary,"exit-${exits.size}.json").apply { writeText(JSONObject().put("Listen","127.0.0.1:$port").put("Secret",secret).put("Context","http://mcrf-bs-fixture").toString()) };android.system.Os.chmod(config.absolutePath,0x180)
    val child=ProcessBuilder(binary.absolutePath,"--config",config.absolutePath).directory(temporary).redirectErrorStream(true).start();exits.add(child)
    workers.submit {child.inputStream.use {input->val buffer=ByteArray(4096);while(input.read(buffer)>=0){}}}
    return ServerImport.read(OpenFluxProfile.share(JSONObject().put("type","openflux").put("name",name).put("negotiate",true).put("secret",secret).put("context","http://mcrf-bs-fixture")
      .put("transports",JSONArray().put(JSONObject().put("type","direct").put("name","direct").put("dial","127.0.0.1:$port"))))).single()
   }
   try {
    val first=exit("First isolated exit");val second=exit("Second isolated exit")
    val wrong=ServerImport.read(OpenFluxProfile.share(JSONObject(first.carrier.toString()).put("name","Rejected isolated key").put("secret",UUID.randomUUID().toString()))).single()
    val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {it.localPort}
    val source=BsPlan.build(listOf(wrong,first,second),5,50,"Cloudflare")
    val fixture=File(temporary,"service-fixture.json").apply {writeText(JSONObject().put("source",source).put("timeout",3000).put("url","http://${address.hostAddress}:${origin.localPort}$path").toString())};android.system.Os.chmod(fixture.absolutePath,0x180)
    Thread.sleep(300)
    context.startForegroundService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.START).putExtra("tun",false).putExtra("port",port).putExtra("test_fixture",fixture.absolutePath))
    verify(await(25){TrafficState.current.server==first.name&&McrfVpnService.status.startsWith("Прокси ")},"BS service refuses a wrong-key candidate and selects a traffic-proven encrypted exit")
    fun fetch(){Socket("127.0.0.1",port).use {socket->socket.soTimeout=10000;socket.getOutputStream().write("GET http://${address.hostAddress}:${origin.localPort}$path HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));val data=ImportIO.read(socket.getInputStream()).toString(Charsets.US_ASCII);verify(data.contains("200 OK")&&data.endsWith("B".repeat(4096)),"Mixed proxy transfers 4KB through the selected BS exit")}}
    fetch();verify(await(5){TrafficState.current.available&&TrafficState.current.received>=4096},"Live BS telemetry counts connection traffic")
    val bytes=TrafficState.current.received
    exits[0].outputStream.write("STOP\n".toByteArray());exits[0].outputStream.flush();exits[0].outputStream.close();exits[0].waitFor(5,TimeUnit.SECONDS)
    verify(await(40){TrafficState.current.server==second.name&&McrfVpnService.status.startsWith("Прокси ")},"Failed active exit switches to another proven candidate without choosing direct")
    fetch();verify(await(5){TrafficState.current.received>bytes},"BS handover preserves total traffic and reports the new server")
    val notices=context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
    verify(notices.any {it.id==1&&it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString().contains(second.name)&&!it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("/с")},"Ongoing notification shows the actual fallback server and only total traffic")
    context.startService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.AUTO_OFF))
    Thread.sleep(500);val afterOff=requests.get();Thread.sleep(6500)
    verify(requests.get()==afterOff&&TrafficState.current.server==second.name&&McrfVpnService.status.startsWith("Прокси "),"Turning BS automatic off stops recurring health probes and keeps the current server")
    fetch()
    verify(TrafficState.current.server==second.name,"Manual connection stays usable after automatic mode is disabled")
   }finally{
    context.startService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.STOP));await(10){McrfVpnService.status=="Отключено"}
    exits.forEach {child->try{child.outputStream.write("STOP\n".toByteArray());child.outputStream.flush();child.outputStream.close()}catch(_:Exception){};if(!child.waitFor(4,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(2,TimeUnit.SECONDS)}}
    running.set(false);workers.shutdownNow()
   }
  }
  verify(ProfileStore.snapshot(context)==before&&context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences,"BS test leaves saved profiles, catalogue, routes and user preferences unchanged")
  verify(context.cacheDir.listFiles()?.none {it.name.startsWith("mcrf-carrier-")||it.name.startsWith("mcrf-run-")}==true,"BS stop removes every private runtime and native child fixture")
 }
}
