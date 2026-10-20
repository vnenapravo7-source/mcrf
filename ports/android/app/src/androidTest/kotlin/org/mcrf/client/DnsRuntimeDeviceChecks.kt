package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.io.File
import java.net.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/** Actual generated DNS rules and isolated installed native worker. Only owned LAN UDP peers. */
object DnsRuntimeDeviceChecks {
    private class Peer(address:InetAddress,private val last:Int):AutoCloseable {
        val socket=DatagramSocket(InetSocketAddress(address,0))
        val requests=AtomicInteger()
        private val thread=Thread({while(!socket.isClosed)try{
            val input=DatagramPacket(ByteArray(1024),1024);socket.receive(input)
            val query=input.data.copyOf(input.length)
            require(query.size in 17..512&&query[2].toInt() and 0x80==0)
            requests.incrementAndGet()
            val response=query.copyOf().apply {this[2]=0x81.toByte();this[3]=0x80.toByte();this[6]=0;this[7]=1}
            val answer=byteArrayOf(0xc0.toByte(),12,0,1,0,1,0,0,0,60,0,4,198.toByte(),51,100,last.toByte())
            val output=response+answer
            socket.send(DatagramPacket(output,output.size,input.socketAddress))
        }catch(error:Exception){if(!socket.isClosed)throw error}},"mcrf-private-dns-peer").apply {isDaemon=true;start()}
        override fun close(){socket.close();thread.join(3000);check(!thread.isAlive)}
    }
    @JvmStatic fun run(test:Instrumentation,passed:Consumer<String>){
        check(BuildConfig.DEBUG)
        val context=test.targetContext;val before=ProfileStore.snapshot(context)
        val address=InetAddress.getByName(LanSharingAddresses.discover().firstOrNull()?:error("No physical IPv4"))
        val name="mcrf-lan-${UUID.randomUUID()}.json"
        val input=File(context.cacheDir,name);val stop=File(context.cacheDir,"$name.stop")
        val ready=CountDownLatch(1);val completed=CountDownLatch(1);var result=2;var startupFailure=""
        val receiver=object:ResultReceiver(Handler(Looper.getMainLooper())){override fun onReceiveResult(code:Int,data:Bundle?){if(code==1)ready.countDown()else{result=code;startupFailure=data?.getString("failure").orEmpty();completed.countDown();ready.countDown()}}}
        val parcel=Parcel.obtain()
        val remote=try{receiver.writeToParcel(parcel,0);parcel.setDataPosition(0);ResultReceiver.CREATOR.createFromParcel(parcel)}finally{parcel.recycle()}
        Peer(address,11).use {direct->Peer(address,22).use {proxy->Peer(address,33).use {list->
            fun dns(peer:Peer)=JSONObject().put("provider","Свой").put("protocol","UDP").put("custom","udp://${address.hostAddress}:${peer.socket.localPort}")
            val suffix="${UUID.randomUUID().toString().replace("-","")}.fixture.test"
            val state=RouteRules.defaults().put("globalDns",dns(proxy)).put("advancedDns",AdvancedDnsConfig.defaults()
                .put("enabled",true).put("cache",false).put("remoteNoAAAA",true).put("directNoAAAA",true)
                .put("direct",dns(direct)).put("local",dns(direct)).put("answers","203.0.113.44 hosts.$suffix"))
            fun rule(id:String,target:String)=JSONObject().put("id",id).put("name",id).put("mode","addresses").put("text","DOMAIN,$id.$suffix").put("target",target)
            state.getJSONArray("rules").put(rule("direct","direct")).put(rule("blocked","block"))
                .put(rule("list","proxy").put("dns",dns(list)))
            val port=DatagramSocket(0).use {it.localPort};require(port>=1024)
            val data=JSONObject().put("port",port).put("dnsPort",port).put("username",UUID.randomUUID().toString())
                .put("password",UUID.randomUUID().toString()).put("dnsState",state)
            check(input.createNewFile());android.system.Os.chmod(input.absolutePath,0x180);input.writeText(data.toString())
            var started=false
            try{
                context.startForegroundService(Intent().setClassName(context.packageName,"${context.packageName}.DebugLanSharingService")
                    .putExtra("fixture",name).putExtra("reply",remote));started=true
                check(ready.await(20,TimeUnit.SECONDS)&&completed.count!=0L){"Native DNS listener did not start: $startupFailure"}
                fun query(label:String,type:Int=1):ByteArray {
                    val id=(System.nanoTime() and 65535).toInt()
                    val out=java.io.ByteArrayOutputStream()
                    out.write(byteArrayOf((id shr 8).toByte(),id.toByte(),1,0,0,1,0,0,0,0,0,0))
                    "$label.$suffix".split('.').forEach {val bytes=it.toByteArray(Charsets.US_ASCII);out.write(bytes.size);out.write(bytes)}
                    out.write(byteArrayOf(0,0,type.toByte(),0,1))
                    return DatagramSocket().use {socket->socket.soTimeout=5000;val bytes=out.toByteArray()
                        socket.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName("127.0.0.1"),port))
                        val packet=DatagramPacket(ByteArray(4096),4096);socket.receive(packet)
                        val response=packet.data.copyOf(packet.length)
                        check(response.size>=12&&response[0]==(id shr 8).toByte()&&response[1]==id.toByte())
                        response
                    }
                }
                fun a(label:String,last:Int){val response=query(label);check(response[3].toInt() and 15==0&&response[7].toInt()==1)
                    check(response.takeLast(4).map {it.toInt() and 255}==listOf(if(label=="hosts")203 else 198,if(label=="hosts")0 else 51,if(label=="hosts")113 else 100,last))}
                a("direct",11);check(direct.requests.get()==1&&proxy.requests.get()==0&&list.requests.get()==0)
                passed.accept("Actual direct-domain DNS query reaches only configured direct UDP resolver")
                a("remote",22);check(proxy.requests.get()==1&&direct.requests.get()==1)
                passed.accept("Actual default DNS query reaches only configured remote resolver")
                a("list",33);check(list.requests.get()==1&&proxy.requests.get()==1)
                passed.accept("Explicit per-list DNS wins over default resolver")
                val counts=listOf(direct.requests.get(),proxy.requests.get(),list.requests.get())
                val blocked=query("blocked");check(blocked[3].toInt() and 15==3)
                a("hosts",44)
                val filtered=query("remote",28);check(filtered[3].toInt() and 15==0&&filtered[6].toInt()==0&&filtered[7].toInt()==0)
                check(listOf(direct.requests.get(),proxy.requests.get(),list.requests.get())==counts)
                passed.accept("Blocked NXDOMAIN, predefined host and disabled AAAA answer locally without resolver requests")
            }finally{
                if(started){stop.createNewFile();check(completed.await(20,TimeUnit.SECONDS)){"DNS native owned retirement not acknowledged"}}
                check(input.delete()||!input.exists());check(stop.delete()||!stop.exists())
                check(ProfileStore.snapshot(context)==before){"DNS fixture changed user state"}
            }
            check(result==0){"DNS worker reported failure: $startupFailure"}
        }}}
        passed.accept("Owned DNS worker and peers cleaned up; saved profiles/settings unchanged; no public DNS/VPN/account used")
    }
}
