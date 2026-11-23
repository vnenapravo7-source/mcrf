package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.io.File
import java.util.function.BooleanSupplier

/** Emulator-only ordinary service entry, with a signed synthetic BS and no primary selection. */
class BsNativeServiceInstrumentation:Instrumentation(){
    private var parameters=Bundle()
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);parameters=arguments?:Bundle();start()}
    override fun onStart(){
        val result=Bundle();var code=Activity.RESULT_CANCELED;var phase="scope"
        val preferences=targetContext.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        val savedPreferences=preferences.all.toMap();var saved:Map<String,String>?=null
        var testCache:File?=null
        fun service()=McrfVpnService::class.java.getDeclaredField("currentService").apply {isAccessible=true}.get(null) as? McrfVpnService
        fun restorePreferences(){val edit=preferences.edit().clear();savedPreferences.forEach {(key,value)->when(value){is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value);is Set<*>->edit.putStringSet(key,value.map {it as String}.toSet());else->error("unexpected preference type")}};check(edit.commit())}
        try{
            check(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
            check(!McrfVpnService.hasActiveSession()&&service()==null)
            val module=targetContext.packageManager.getApplicationInfo(BsEngineContract.PACKAGE,0)
            check(module.flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY!=0&&BsNativeContract.advertised(targetContext))
            saved=ProfileStore.snapshot(targetContext)
            val port=java.net.ServerSocket(0).use {it.localPort}
            val testedGeneration=parameters.getString("generation","2.5.3");check(testedGeneration in setOf("2.5.1","2.5.3"))
            val profile=checkNotNull(Csqtt25Profile.fromJson("owned-service",JSONObject().put("type","csqtt").put("csqtt_generation","2.5").put("csqtt_implementation","swg-panel-$testedGeneration").put("server","192.0.2.1").put("server_port",9).put("password","owned-fixture-only").put("workers",10).put("hashes",JSONArray(listOf("owned_fixture_only_123456")))).carrier)
            check(BsNativeContract.advertised(targetContext,profile))
            val auto=parameters.getString("auto")=="true"
            val storedProfile=if(auto)CsqttAutoDetection.reset(profile).also {automatic->
                val target=CsqttAutoDetection.Target("2.5","swg-panel-$testedGeneration")
                testCache=File(File(targetContext.filesDir,"csqtt-generation-cache"),CsqttAutoDetection.key(automatic))
                check(CsqttAutoRuntime.cached(targetContext,automatic)==null)
                CsqttAutoRuntime.connect(targetContext,automatic,BooleanSupplier {false}) {concrete->
                    if(CsqttGenerations.implementation(concrete)!=target.implementation)
                        throw CsqttNativeFailure.Rejected(CsqttNativeFailure.Kind.PROTOCOL)
                }
                check(CsqttAutoRuntime.cached(targetContext,automatic)==target)
            }else profile
            val source=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("type","socks").put("tag","carrier").put("server","127.0.0.1").put("server_port",1)).put(JSONObject().put("type","direct").put("tag","direct")))
                .put("route",JSONObject().put("final","carrier"))
                .put("dns",JSONObject().put("servers",JSONArray().put(JSONObject().put("type","hosts").put("tag","owned").put("predefined",JSONObject().put("owned.invalid","198.51.100.9")))).put("final","owned"))
                .put("mcrf_carrier",JSONObject(storedProfile.toString()).put("outbound_tag","carrier"))
            ProfileStore(targetContext,"preferences.enc").write("{}")
            LocalProxySettings.save(targetContext,LocalProxyConfig.generate(true))
            ProfileStore(targetContext).write(source.toString())
            check(preferences.edit().remove(ClientPrimarySelection.KEY).putBoolean("automatic",false).putBoolean("catalogueProfile",false).putBoolean(BsDetectionMonitor.KEY,false).putBoolean("wantedRunning",false).commit())
            phase="ordinary START without primary selection"
            targetContext.startForegroundService(Intent(targetContext,McrfVpnService::class.java).setAction(McrfVpnService.START).putExtra("tun",true).putExtra("preparedProxyPort",port).putExtra("applyRoutes",false).putExtra("pendingWhitelists",true))
            val coreField=McrfVpnService::class.java.getDeclaredField("core").apply {isAccessible=true}
            var worker:ClientPrimaryWorker?=null;val deadline=SystemClock.elapsedRealtime()+15000
            while(SystemClock.elapsedRealtime()<deadline){worker=service()?.let {coreField.get(it) as? ClientPrimaryWorker};if(worker?.state=="RUNNING")break;Thread.sleep(20)}
            check(worker?.choiceId==BsNativeContract.CHOICE&&worker?.state=="RUNNING") {"ordinary BS worker did not start: "+McrfVpnService.status}
            phase="ordinary service TUN DNS"
            val manager=targetContext.getSystemService(android.net.ConnectivityManager::class.java)
            val vpn=checkNotNull(manager.allNetworks.firstOrNull {manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)==true})
            val dns=checkNotNull(manager.getLinkProperties(vpn)?.dnsServers?.firstOrNull())
            val query=PacketDnsWire.query("owned.invalid",1,2331)
            java.net.DatagramSocket(null).use {socket->vpn.bindSocket(socket);socket.bind(java.net.InetSocketAddress(0));socket.connect(dns,53);socket.soTimeout=5000;socket.send(java.net.DatagramPacket(query,query.size));val reply=java.net.DatagramPacket(ByteArray(2048),2048);socket.receive(reply);check(PacketDnsWire.addresses(query,reply.data.copyOfRange(reply.offset,reply.offset+reply.length))==listOf(InetAddress.getByName("198.51.100.9"))){"SERVICE_TUN_DNS"}}
            phase="ordinary service authenticated proxy and direct loopback"
            java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {echo->
                echo.soTimeout=5000;val payload="SSH-2.0-owned-service\r\n".toByteArray();val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
                val echoThread=Thread {try{echo.accept().use {peer->peer.soTimeout=5000;val bytes=peer.getInputStream().readBytes();check(bytes.contentEquals(payload));peer.getOutputStream().write(bytes);peer.shutdownOutput()}}catch(error:Throwable){failure.set(error)}}.apply {isDaemon=true;start()}
                Socket("127.0.0.1",port).use {socket->
                    socket.soTimeout=5000;val input=DataInputStream(socket.getInputStream());val output=DataOutputStream(socket.getOutputStream())
                    val auth=ClientTunStore(targetContext).auth();val method=if(auth.getBoolean("enabled"))2 else 0
                    output.write(byteArrayOf(5,1,method.toByte()));output.flush();check(input.readUnsignedByte()==5&&input.readUnsignedByte()==method){"SERVICE_SOCKS_METHOD"}
                    if(method==2){val user=auth.getString("username").toByteArray();val password=auth.getString("password").toByteArray();output.write(byteArrayOf(1,user.size.toByte())+user+byteArrayOf(password.size.toByte())+password);output.flush();check(input.readUnsignedByte()==1&&input.readUnsignedByte()==0){"SERVICE_SOCKS_AUTH"}}
                    output.write(byteArrayOf(5,1,0,1,127,0,0,1));output.writeShort(echo.localPort);output.flush();val ack=ByteArray(10);input.readFully(ack);check(ack[1].toInt()==0){"SERVICE_SOCKS_CONNECT"}
                    output.write(payload);output.flush();socket.shutdownOutput();check(input.readBytes().contentEquals(payload)){"SERVICE_PROXY_ECHO"}
                }
                echoThread.join(5000);check(!echoThread.isAlive);failure.get()?.let {throw it}
            }
            check(ClientPrimarySelection.selected(targetContext)==null){"SERVICE_PRIMARY_SELECTION_CHANGED"}
            if(auto)check(CsqttGenerations.automatic(JSONObject(ProfileStore(targetContext).read()).getJSONObject("mcrf_carrier"))){"SERVICE_AUTO_PROFILE_REWRITTEN"}
            result.putString("scope","ordinary START without selected primary -> signed standalone BS worker -> Main TUN DNS + own authenticated proxy/direct loopback; stop retires worker during startup health checks; synthetic no provider")
            result.putString("generation",testedGeneration)
            result.putBoolean("auto",auto)
            code=Activity.RESULT_OK
        }catch(error:Throwable){result.putString("phase",phase);result.putString("failure",error.javaClass.simpleName+": "+error.message)}
        finally{
            if(service()!=null){targetContext.startService(Intent(targetContext,McrfVpnService::class.java).setAction(McrfVpnService.STOP));val deadline=SystemClock.elapsedRealtime()+10000;while(service()!=null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);if(service()!=null){code=Activity.RESULT_CANCELED;result.putString("stop","service did not retire")}}
            testCache?.delete()
            saved?.let {ProfileStore.replaceAll(targetContext,it)};restorePreferences()
        }
        finish(code,result)
    }
}



