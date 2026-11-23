package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** SDK-only architectural experiment on the owned emulator. No Main changes,
 * provider, external peer, settings edits or phone execution. Only reserved
 * documentation addresses are routed into a temporary, unforwarded TUN. */
class BsOwnedNetworkBindingInstrumentation:Instrumentation() {
    private var fixturePort=0
    private var socketProbePort=0
    override fun onCreate(arguments:Bundle?){fixturePort=arguments?.getString("physicalFixturePort")?.toIntOrNull()?:0;socketProbePort=arguments?.getString("vpnSocketFixturePort")?.toIntOrNull()?:0;super.onCreate(arguments);start()}
    override fun onStart(){
        val result=Bundle();var code=Activity.RESULT_CANCELED;var tun:ParcelFileDescriptor?=null
        val rows=JSONArray();val sockets=mutableListOf<Socket>();val threads=mutableListOf<Thread>()
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            val manager=targetContext.getSystemService(ConnectivityManager::class.java)
            check(manager.allNetworks.none{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}){"EXISTING_VPN_NOT_TOUCHED"}
            check(VpnService.prepare(targetContext)==null){"OWNED_EMULATOR_MAIN_VPN_CONSENT_REQUIRED"}
            val physical=manager.activeNetwork?.takeIf{val c=manager.getNetworkCapabilities(it);c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==false&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}
            if(fixturePort!=0)check(fixturePort in 1024..65535&&physical!=null)
            if(socketProbePort!=0)check(socketProbePort in 1024..65535&&physical!=null&&fixturePort==0)
            val before=ProfileStore.snapshot(targetContext)
            val facade=McrfVpnService()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java).apply {isAccessible=true}.invoke(facade,targetContext)
            for(excluded in listOf(true,false)) {
                val builder=facade.Builder().setSession("MCRF owned binding fixture").setMtu(1500)
                    .addAddress("198.18.0.1",32).addRoute("192.0.2.0",24)
                if(fixturePort!=0||socketProbePort!=0)builder.addRoute("10.0.2.2",32)
                if(excluded)builder.addDisallowedApplication(targetContext.packageName) else builder.addAllowedApplication(targetContext.packageName)
                tun=requireNotNull(builder.establish());val fd=tun!!.fileDescriptor
                Os.fcntlInt(fd,OsConstants.F_SETFL,Os.fcntlInt(fd,OsConstants.F_GETFL,0) or OsConstants.O_NONBLOCK)
                var vpn:android.net.Network?=null;val networkDeadline=SystemClock.elapsedRealtime()+4000
                while(vpn==null&&SystemClock.elapsedRealtime()<networkDeadline){vpn=manager.allNetworks.firstOrNull{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true};if(vpn==null)Thread.sleep(20)}
                if(vpn==null){
                    rows.put(JSONObject().put("uidExcluded",excluded).put("vpnNetworkVisible",false).put("tunSynObserved",false))
                    tun!!.close();tun=null;Thread.sleep(100);continue
                }
                val socket=Socket();sockets+=socket;socket.bind(InetSocketAddress(0))
                val row=JSONObject().put("uidExcluded",excluded).put("vpnNetworkVisible",true).put("forcedBindAccepted",false).put("tunSynObserved",false)
                if(!excluded&&socketProbePort!=0){
                    val fixture=java.io.File(context.applicationInfo.nativeLibraryDir,"libmcrf_bs_dns_fixture.so")
                    val metadata=JSONObject(context.assets.open("bs-dns-kernel-fixture.json").bufferedReader().use{it.readText()})
                    check(fixture.isFile&&fixture.length()==metadata.getLong("bytes"))
                    val digest=java.security.MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString(""){"%02x".format(it)};check(digest==metadata.getString("sha256"))
                    val child=ProcessBuilder(fixture.absolutePath,"network-probe",java.lang.Long.toUnsignedString(physical!!.networkHandle),java.lang.Long.toUnsignedString(vpn!!.networkHandle),"10.0.2.2:$socketProbePort").redirectErrorStream(true).start()
                    try{check(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));val output=child.inputStream.bufferedReader().readText();check(child.exitValue()==0&&output.trim()=="OWNED_CHILD_PHYSICAL_AND_SOCKET_VPN_OK"){"OWNED_SOCKET_PROBE_FAILED: $output"}}finally{child.destroyForcibly()}
                    val packet=ByteArray(2048);var socketSyn=false
                    while(true){val n=try{Os.read(fd,packet,0,packet.size)}catch(e:android.system.ErrnoException){if(e.errno!=OsConstants.EAGAIN)throw e;0};if(n==0)break
                        if(n>=40&&packet[9].toInt()==6&&packet.copyOfRange(16,20).contentEquals(byteArrayOf(10,0,2,2))){val header=(packet[0].toInt()and 15)*4
                            if(n>=header+20&&(((packet[header+2].toInt()and 255)shl 8)or(packet[header+3].toInt()and 255))==socketProbePort&&(packet[header+13].toInt()and 2)!=0)socketSyn=true
                        }
                    }
                    check(socketSyn){"VPN_BOUND_CHILD_SOCKET_NOT_SEEN_IN_TUN"}
                    row.put("sameChildPhysicalEcho",true).put("sameChildVpnSocketInTun",true)
                }
                if(!excluded&&fixturePort!=0){
                    val fixture=java.io.File(context.applicationInfo.nativeLibraryDir,"libmcrf_bs_net_fixture.so")
                    val metadata=JSONObject(context.assets.open("bs-network-fixture.json").bufferedReader().use{it.readText()}).getJSONObject("binary")
                    check(fixture.isFile&&fixture.length()==metadata.getLong("bytes"))
                    val digest=java.security.MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString(""){"%02x".format(it)}
                    check(digest==metadata.getString("sha256")){"FIXTURE_HASH_MISMATCH"}
                    fun runFixture(mode:String,handle:String):String {
                        val child=ProcessBuilder(fixture.absolutePath,mode,handle,"10.0.2.2:$fixturePort").redirectErrorStream(true).start()
                        try{check(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){"FIXTURE_TIMEOUT"};val output=child.inputStream.bufferedReader().readText();check(child.exitValue()==0){"FIXTURE_FAILED: $output"};return output.trim()}finally{child.destroyForcibly()}
                    }
                    check(runFixture("default","0")=="OWNED_DEFAULT_CONNECT_FAILED"){"DEFAULT_CHILD_ESCAPED_TUN"}
                    val packet=ByteArray(2048);var defaultSyn=false
                    while(true){val n=try{Os.read(fd,packet,0,packet.size)}catch(e:android.system.ErrnoException){if(e.errno!=OsConstants.EAGAIN)throw e;0};if(n==0)break;if(n>=40&&packet[9].toInt()==6&&packet.copyOfRange(16,20).contentEquals(byteArrayOf(10,0,2,2)))defaultSyn=true}
                    check(defaultSyn){"DEFAULT_CHILD_NOT_SEEN_IN_TUN"}
                    check(runFixture("bound",java.lang.Long.toUnsignedString(physical!!.networkHandle))=="OWNED_NETWORK_ECHO_OK")
                    var boundLeak=false
                    while(true){val n=try{Os.read(fd,packet,0,packet.size)}catch(e:android.system.ErrnoException){if(e.errno!=OsConstants.EAGAIN)throw e;0};if(n==0)break;if(n>=40&&packet.copyOfRange(16,20).contentEquals(byteArrayOf(10,0,2,2)))boundLeak=true}
                    check(!boundLeak){"BOUND_CHILD_ENTERED_TUN"}
                    row.put("defaultChildInTun",true).put("physicalChildEcho",true).put("physicalChildOutsideTun",true)
                }
                try{vpn!!.bindSocket(socket);row.put("forcedBindAccepted",true)}catch(error:Exception){row.put("bindFailureType",error.javaClass.simpleName)}
                if(row.getBoolean("forcedBindAccepted")) {
                    val connection=Thread({try{socket.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(),0,2,42)),443),1800)}catch(_:Exception){}},"mcrf-owned-tun-connect").apply{isDaemon=true;start()};threads+=connection
                    val bytes=ByteArray(2048);val deadline=SystemClock.elapsedRealtime()+1500
                    while(SystemClock.elapsedRealtime()<deadline&&!row.getBoolean("tunSynObserved")) {
                        val count=try{Os.read(fd,bytes,0,bytes.size)}catch(error:android.system.ErrnoException){if(error.errno!=OsConstants.EAGAIN)throw error;0}
                        if(count>=40&&(bytes[0].toInt() and 0xf0)==0x40&&bytes[9].toInt()==6) {
                            val header=(bytes[0].toInt() and 15)*4
                            if(count>=header+20){val port=((bytes[header].toInt()and 255)shl 8)or(bytes[header+1].toInt()and 255)
                                if(port==socket.localPort){
                                    val source=InetAddress.getByAddress(bytes.copyOfRange(12,16));val destination=InetAddress.getByAddress(bytes.copyOfRange(16,20))
                                    val owner=manager.getConnectionOwnerUid(OsConstants.IPPROTO_TCP,InetSocketAddress(source,port),InetSocketAddress(destination,443))
                                    check(owner==android.os.Process.myUid()){ "KERNEL_OWNER_MISMATCH" }
                                    row.put("tunSynObserved",true).put("kernelOwnerMatches",true)
                                }
                            }
                        }
                        if(count==0)Thread.sleep(5)
                    }
                }
                socket.close();threads.forEach{it.join(2500)};threads.clear();tun!!.close();tun=null
                rows.put(row)
                val gone=SystemClock.elapsedRealtime()+3000
                while(manager.allNetworks.any{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}&&SystemClock.elapsedRealtime()<gone)Thread.sleep(20)
                check(manager.allNetworks.none{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}){"OWNED_TUN_NOT_RETIRED"}
            }
            check(ProfileStore.snapshot(targetContext)==before){"BINDING_FIXTURE_CHANGED_SAVED_PROFILES"}
            result.putString("observations",rows.toString());result.putString("scope","Owned SDK/TUN/kernel-owner experiment only; no implemented BS DNS or provider claim")
            code=Activity.RESULT_OK
        }catch(error:Throwable){result.putString("failure",error.javaClass.simpleName+": "+error.message);result.putString("observations",rows.toString())}
        finally{sockets.forEach{runCatching{it.close()}};runCatching{tun?.close()};threads.forEach{it.join(2500)}}
        finish(code,result)
    }
}
