package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.net.*
import android.os.*
import java.net.*
import java.io.*
import java.util.concurrent.*
import org.json.JSONObject

/** Owned emulator only: synthetic DNS to an app-owned listener through actual
 * TUN, native Rust reflection and the Android kernel TCP/UDP endpoints. No
 * provider, upstream DNS, profile edit, or production capability assertion. */
class BsDnsKernelInstrumentation:Instrumentation(){
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val result=Bundle();var code=Activity.RESULT_CANCELED
        var tun:ParcelFileDescriptor?=null;var child:java.lang.Process?=null;var uds:LocalServerSocket?=null
        val owned=mutableListOf<Closeable>();val workers=Executors.newFixedThreadPool(5)
        try{
            check(BuildConfig.DEBUG&&Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            val manager=targetContext.getSystemService(ConnectivityManager::class.java)
            check(manager.allNetworks.none{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}){"EXISTING_VPN_NOT_TOUCHED"}
            check(VpnService.prepare(targetContext)==null)
            val before=ProfileStore.snapshot(targetContext)
            val fixture=File(context.applicationInfo.nativeLibraryDir,"libmcrf_bs_dns_fixture.so")
            val metadata=JSONObject(context.assets.open("bs-dns-kernel-fixture.json").bufferedReader().use{it.readText()})
            check(Build.SUPPORTED_ABIS.first()==metadata.getString("abi")&&fixture.length()==metadata.getLong("bytes"))
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString(""){"%02x".format(it)};check(hash==metadata.getString("sha256"))
            val facade=McrfVpnService();ContextWrapper::class.java.getDeclaredMethod("attachBaseContext",android.content.Context::class.java).apply{isAccessible=true}.invoke(facade,targetContext)
            tun=requireNotNull(facade.Builder().setSession("MCRF owned DNS fixture").setMtu(1500)
                .addAddress("10.89.0.2",32).addAddress("fd00::2",128)
                .addRoute("198.19.255.53",32).addRoute("fdfe:dcba:9876:dd54::53",128)
                .addAllowedApplication(targetContext.packageName).establish())
            var network:Network?=null;val networkDeadline=SystemClock.elapsedRealtime()+4000
            while(network==null&&SystemClock.elapsedRealtime()<networkDeadline){network=manager.allNetworks.firstOrNull{manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true};if(network==null)Thread.sleep(20)}
            val vpn=requireNotNull(network)
            val servers=mutableListOf<ServerSocket>();val datagrams=mutableListOf<DatagramSocket>();var port=0
            val query=PacketDnsWire.query("owned.invalid",1,1771)
            val reply=PacketDnsWire.reply(query,0,listOf(InetAddress.getByAddress(byteArrayOf(198.toByte(),51,100,9))))
            val jobs=mutableListOf<Future<*>>()
            for(address in listOf("10.89.0.2","fd00::2")){
                val local=InetAddress.getByName(address)
                val server=ServerSocket();owned+=server;server.bind(InetSocketAddress(local,port),1);server.soTimeout=6000;if(port==0)port=server.localPort;servers+=server
                val udp=DatagramSocket(null);owned+=udp;vpn.bindSocket(udp);udp.bind(InetSocketAddress(local,port));udp.soTimeout=6000;datagrams+=udp
                jobs+=workers.submit{
                    server.accept().use{socket->socket.soTimeout=5000;val input=DataInputStream(socket.getInputStream());val size=input.readUnsignedShort();check(size==query.size);val bytes=ByteArray(size);input.readFully(bytes);check(bytes.contentEquals(query));DataOutputStream(socket.getOutputStream()).apply{writeShort(reply.size);write(reply);flush()}}
                }
                jobs+=workers.submit{
                    val packet=DatagramPacket(ByteArray(4096),4096);udp.receive(packet);check(packet.data.copyOfRange(packet.offset,packet.offset+packet.length).contentEquals(query));udp.send(DatagramPacket(reply,reply.size,packet.address,packet.port))
                }
            }
            val name="mcrf-dns-kernel-${java.util.UUID.randomUUID()}";uds=LocalServerSocket(name);val server=requireNotNull(uds)
            val accept=workers.submit<LocalSocket>{server.accept()}
            child=ProcessBuilder(fixture.absolutePath,name,android.os.Process.myPid().toString(),port.toString()).redirectErrorStream(true).start()
            accept.get(5,TimeUnit.SECONDS).use{socket->
                check(socket.peerCredentials.uid==android.os.Process.myUid()&&socket.peerCredentials.pid>0&&child!!.isAlive)
                socket.soTimeout=5000;socket.setFileDescriptorsForSend(arrayOf(tun!!.fileDescriptor));socket.outputStream.write(1);socket.outputStream.flush();check(socket.inputStream.read()==1)
            }
            val observations=org.json.JSONArray()
            for(alias in listOf("198.19.255.53","fdfe:dcba:9876:dd54::53")){
                val remote=InetAddress.getByName(alias)
                DatagramSocket(null).use{socket->vpn.bindSocket(socket);socket.bind(InetSocketAddress(0));socket.connect(remote,53);socket.soTimeout=5000
                    check(manager.getConnectionOwnerUid(17,InetSocketAddress(socket.localAddress,socket.localPort),InetSocketAddress(remote,53))==android.os.Process.myUid())
                    socket.send(DatagramPacket(query,query.size));val packet=DatagramPacket(ByteArray(4096),4096);socket.receive(packet);check(packet.data.copyOfRange(packet.offset,packet.offset+packet.length).contentEquals(reply))
                }
                Socket().use{socket->socket.bind(InetSocketAddress(0));vpn.bindSocket(socket);socket.connect(InetSocketAddress(remote,53),5000);socket.soTimeout=5000
                    check(manager.getConnectionOwnerUid(6,InetSocketAddress(socket.localAddress,socket.localPort),InetSocketAddress(remote,53))==android.os.Process.myUid())
                    DataOutputStream(socket.getOutputStream()).apply{writeShort(query.size);write(query);flush()};val input=DataInputStream(socket.getInputStream());val size=input.readUnsignedShort();check(size==reply.size);val bytes=ByteArray(size);input.readFully(bytes);check(bytes.contentEquals(reply))
                }
                observations.put(JSONObject().put("family",remote.address.size*8).put("udp",true).put("tcp",true).put("kernelOwner",true))
            }
            jobs.forEach{it.get(2,TimeUnit.SECONDS)}
            check(child!!.waitFor(18,TimeUnit.SECONDS)){"OWNED_CHILD_TIMEOUT"}
            val output=child!!.inputStream.bufferedReader().readText();check(child!!.exitValue()==0&&output.contains("OWNED_DNS_REFLECTOR_DONE")){"OWNED_CHILD_FAILED: $output"}
            check(ProfileStore.snapshot(targetContext)==before)
            result.putString("observations",observations.toString());result.putString("scope","Actual owned TUN/Rust/kernel DNS reflection only; no production DNS plan/provider test")
            code=Activity.RESULT_OK
        }catch(error:Throwable){result.putString("failure",error.javaClass.simpleName+": "+error.message);if(child?.isAlive==false)result.putString("fixtureFailure",child!!.inputStream.bufferedReader().readText().take(2000))}
        finally{runCatching{uds?.close()};child?.destroyForcibly();owned.forEach{runCatching{it.close()}};runCatching{tun?.close()};workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS)}
        finish(code,result)
    }
}
