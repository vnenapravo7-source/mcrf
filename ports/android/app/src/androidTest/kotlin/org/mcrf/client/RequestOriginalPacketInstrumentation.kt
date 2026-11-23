package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owned emulator only: the real app UID receives SCM_RIGHTS from its exact Go
 * child. Never loads a provider profile, starts a VPN or changes saved settings. */
class RequestOriginalPacketInstrumentation:Instrumentation() {
    private var moduleBridge=false
    private var moduleTraffic=false
    private var allowPhone=false
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);moduleBridge=arguments?.getString("moduleBridge")=="true";moduleTraffic=arguments?.getString("moduleTraffic")=="true";allowPhone=arguments?.getString("allowPhone")=="true";start()}
    override fun onStart() {
        val result=Bundle()
        try {
            check(BuildConfig.DEBUG&&(android.os.Build.MODEL.contains("sdk",true)||allowPhone))
            check(!McrfVpnService.hasActiveSession())
            val before=ProfileStore.snapshot(targetContext)
            val executable=File(context.applicationInfo.nativeLibraryDir,"libmcrf_original_packet_fixture.so").canonicalFile
            val proof=context.assets.open("original-packet-fixture/receipt.json").bufferedReader().use {JSONObject(it.readText())}
            check(proof.getString("abi")==android.os.Build.SUPPORTED_ABIS.first())
            check(executable.isFile&&executable.length()==proof.getLong("bytes"))
            check(MessageDigest.getInstance("SHA-256").digest(executable.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)}==proof.getString("sha256"))
            val directory=File(targetContext.cacheDir,"mcrf-original-packet-test-${java.util.UUID.randomUUID()}")
            check(directory.mkdir())
            try {roundTrip(executable,directory);cancelAccept(executable,directory);if(moduleBridge)moduleAdoption(executable,directory)}
            finally{check(directory.delete()){ "Owned fixture directory was not empty" }}
            check(ProfileStore.snapshot(targetContext)==before)
            result.putString("result","PASS: app UID/Go ownership/FD/packet/retirement checks; module SPI adoption=$moduleBridge TCP+fragmentedUDP=$moduleTraffic; profiles unchanged; no provider/throughput claim")
            finish(Activity.RESULT_OK,result)
        }catch(error:Throwable) {
            android.util.Log.e("McrfOriginalPacket","Owned app FD fixture failed",error)
            result.putString("failure",error.javaClass.name+": "+error.message)
            finish(Activity.RESULT_CANCELED,result)
        }
    }
    private fun moduleAdoption(executable:File,directory:File) {
        val installation=EngineUpdatePrimary.installation(targetContext,"sing-box")
        check(installation.metaData?.get(OriginalCarrierMode.PRIMARY_METADATA)==OriginalCarrierPacketSpec.CAPABILITY)
        val platform=java.lang.reflect.Proxy.newProxyInstance(org.mcrf.engine.spi.v1.Platform::class.java.classLoader,
            arrayOf(org.mcrf.engine.spi.v1.Platform::class.java)){_,method,args->
            if(!moduleTraffic)throw UnsupportedOperationException("PRESTART_FIXTURE_PLATFORM_${method.name}")
            when(method.name) {
                "features"->org.mcrf.engine.spi.v1.Platform.Features(false,false,false,false,
                    org.mcrf.engine.spi.v1.Platform.Availability.EMPTY_COMPATIBILITY,org.mcrf.engine.spi.v1.Platform.Availability.EMPTY_COMPATIBILITY,
                    org.mcrf.engine.spi.v1.Platform.Availability.EMPTY_COMPATIBILITY,org.mcrf.engine.spi.v1.Platform.Availability.EMPTY_COMPATIBILITY,
                    org.mcrf.engine.spi.v1.Platform.Availability.UNSUPPORTED,org.mcrf.engine.spi.v1.Platform.Availability.UNSUPPORTED)
                "interfaces"->listOf(org.mcrf.engine.spi.v1.Platform.InterfaceInfo("lo",1,65536,73,"other",false,listOf("127.0.0.1/8"),emptyList()))
                "startDefaultMonitor"->{(args!![1] as org.mcrf.engine.spi.v1.Platform.DefaultListener).update(39,org.mcrf.engine.spi.v1.Platform.DefaultInterface("lo",1,false,false));null}
                "closeDefaultMonitor","traffic","clearDnsCache"->null
                else->throw UnsupportedOperationException("FIXTURE_PLATFORM_${method.name}")
            }
        } as org.mcrf.engine.spi.v1.Platform
        val worker=ClientPrimaryWorker.connect(targetContext,"singbox",39,platform)
        val owner=CarrierProcessGroup(executable,directory)
        val control=CarrierOriginalPacketControl(owner,39){false}
        var child:Process?=null
        val timer=Executors.newSingleThreadScheduledExecutor()
        val udp=java.net.DatagramSocket(0,java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val port=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use {it.localPort}
            val config=JSONObject().put("outbounds",org.json.JSONArray().put(JSONObject().put("type","socks").put("tag","original-fixture").put("server","127.0.0.1").put("server_port",1)))
                .put("inbounds",org.json.JSONArray().put(JSONObject().put("type","mixed").put("listen","127.0.0.1").put("listen_port",port)))
                .put("route",JSONObject().put("final","original-fixture"))
            if(moduleTraffic) {
                config.getJSONArray("outbounds").put(JSONObject().put("type","socks").put("tag","unavailable-fallback").put("server","127.0.0.1").put("server_port",1))
                config.put("route",JSONObject().put("final","unavailable-fallback").put("rules",org.json.JSONArray()
                    .put(JSONObject().put("domain",org.json.JSONArray().put("carrier.fixture.test")).put("outbound","original-fixture"))
                    .put(JSONObject().put("ip_cidr",org.json.JSONArray().put("198.18.0.2/32")).put("outbound","original-fixture"))))
                config.put("dns",JSONObject().put("servers",org.json.JSONArray().put(JSONObject().put("type","hosts").put("tag","fixture-dns")
                    .put("predefined",JSONObject().put("carrier.fixture.test",org.json.JSONArray().put("198.18.0.2"))))).put("final","fixture-dns"))
            }
            val settings=ClientPrimarySelection.envelope(config,false,false)
            val childTest=if(moduleTraffic)"TestMcrfModuleCarrierChild"else"TestMcrfOriginalPacketPortChild"
            val builder=ProcessBuilder(executable.path,"-test.run=^$childTest$","-test.timeout=8s").directory(directory).redirectErrorStream(true)
            builder.environment().apply {
                put("MCRF_ORIGINAL_PACKET_CHILD",control.privateConfiguration().toString())
                put("MCRF_PROCESS_OWNER",owner.nonce);put("MCRF_ANDROID_OWNER_FIXTURE","1")
                put("MCRF_ORIGINAL_PACKET_TEST_MODE","relay@127.0.0.1:${udp.localPort}")
            }
            val process=builder.start();child=process
            val timeout=timer.schedule({if(process.isAlive)process.destroyForcibly()},8,TimeUnit.SECONDS)
            val stdout=process.inputStream.bufferedReader()
            val ack=owner.bind(checkNotNull(stdout.readLine()))
            process.outputStream.apply {write(ack.toByteArray(Charsets.US_ASCII));flush()}
            val endpoint=control.await(SystemClock.elapsedRealtime()+3_000)
            endpoint.transferOwned {fd,metadata->
                worker.attachOriginalCarrier(settings,OriginalCarrierPacketSpec("original-fixture",metadata.nonce,metadata.local,metadata.mtu),fd,metadata.peerPid,metadata.generation)
            }
            // No START: module ownership must survive Main's pending duplicates,
            // then STOP must close the adopted descriptor without network calls.
            check(process.isAlive)
            if(moduleTraffic) {
                worker.start("",settings,org.mcrf.engine.spi.v1.Engine.Mode.PROXY)
                for(destination in listOf(java.net.InetSocketAddress("198.18.0.2",18080),java.net.InetSocketAddress.createUnresolved("carrier.fixture.test",18080)))
                java.net.Socket(java.net.Proxy(java.net.Proxy.Type.SOCKS,java.net.InetSocketAddress("127.0.0.1",port))).use {socket->
                    socket.soTimeout=1500;socket.connect(destination,1500)
                    val payload=ByteArray(64){(it*3).toByte()};socket.getOutputStream().write(payload)
                    val reply=ByteArray(64);java.io.DataInputStream(socket.getInputStream()).readFully(reply);check(reply.contentEquals(payload))
                }
                java.net.Socket("127.0.0.1",port).use {socket->
                    socket.soTimeout=1500;val input=java.io.DataInputStream(socket.getInputStream());val output=socket.getOutputStream()
                    output.write(byteArrayOf(5,1,0));check(input.readUnsignedByte()==5&&input.readUnsignedByte()==0)
                    output.write(byteArrayOf(5,3,0,1,0,0,0,0,0,0));check(input.readUnsignedByte()==5&&input.readUnsignedByte()==0);input.readUnsignedByte()
                    check(input.readUnsignedByte()==1);val address=ByteArray(4);input.readFully(address);val relay=input.readUnsignedShort()
                    java.net.DatagramSocket().use {datagram->
                        datagram.soTimeout=1500;val payload=ByteArray(4096){(it*7).toByte()}
                        val wire=byteArrayOf(0,0,0,1,198.toByte(),18,0,2,70,161.toByte())+payload
                        datagram.send(java.net.DatagramPacket(wire,wire.size,java.net.InetAddress.getByName("127.0.0.1"),relay))
                        val reply=java.net.DatagramPacket(ByteArray(8192),8192);datagram.receive(reply)
                        check(reply.length==payload.size+10&&reply.data.copyOfRange(10,reply.length).contentEquals(payload))
                    }
                }
            }
            worker.close()
            check(process.waitFor(2,TimeUnit.SECONDS)&&process.exitValue()==0){"MODULE_ADOPTION_RETIREMENT: ${stdout.readText().take(512)}"}
            owner.awaitDeath();timeout.cancel(false)
        }finally {
            control.close();worker.close();udp.close();timer.shutdownNow()
            child?.let {if(it.isAlive){it.destroyForcibly();check(it.waitFor(2,TimeUnit.SECONDS))}}
        }
    }
    private fun roundTrip(executable:File,directory:File) {
        val owner=CarrierProcessGroup(executable,directory)
        val control=CarrierOriginalPacketControl(owner,37){false}
        val stranger=LocalSocket()
        var process:Process?=null
        var owned:ParcelFileDescriptor?=null
        val watchdog=Executors.newSingleThreadScheduledExecutor()
        try {
            val configuration=control.privateConfiguration()
            // A process with our UID but the wrong PID must not claim the FD role.
            stranger.connect(LocalSocketAddress(configuration.getString("name"),LocalSocketAddress.Namespace.ABSTRACT))
            val builder=ProcessBuilder(executable.path,"-test.run=^TestMcrfOriginalPacketPortChild$","-test.timeout=8s")
                .directory(directory).redirectErrorStream(true)
            builder.environment().apply {
                put("MCRF_ORIGINAL_PACKET_CHILD",configuration.toString())
                put("MCRF_PROCESS_OWNER",owner.nonce)
                put("MCRF_ANDROID_OWNER_FIXTURE","1")
                put("MCRF_ORIGINAL_PACKET_TEST_MODE","normal")
            }
            val child=builder.start();process=child
            val timeout=watchdog.schedule({if(child.isAlive)child.destroyForcibly()},8,TimeUnit.SECONDS)
            val stdout=child.inputStream.bufferedReader()
            val line=checkNotNull(stdout.readLine()){"Owned Go child exited before identity"}
            val ack=owner.bind(line)
            child.outputStream.apply {write(ack.toByteArray(Charsets.US_ASCII));flush()}
            val endpoint=control.await(SystemClock.elapsedRealtime()+3_000)
            check(endpoint.metadata.peerPid==owner.verifiedRootPid()&&endpoint.metadata.generation==37L&&endpoint.metadata.mtu==1300)
            endpoint.transferOwned {descriptor,metadata->
                check(metadata.nonce==owner.nonce&&metadata.local=="198.18.0.1")
                owned=ParcelFileDescriptor.dup(descriptor.fileDescriptor)
            }
            val held=checkNotNull(owned)
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(held.fileDescriptor)).use {input->
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(held.fileDescriptor)).use {output->
                    repeat(3){index->
                        val packet=ByteArray(36+index*8)
                        packet[0]=0x45;packet[2]=(packet.size shr 8).toByte();packet[3]=packet.size.toByte();packet[8]=64;packet[9]=17
                        byteArrayOf(198.toByte(),18,0,1).copyInto(packet,12);byteArrayOf(198.toByte(),18,0,2).copyInto(packet,16)
                        for(i in 20 until packet.size)packet[i]=(i+index).toByte()
                        if(index==1)packet[6]=0x60
                        if(index==2){packet[6]=0x40;packet[7]=3}
                        output.write(packet);output.flush()
                        val response=ByteArray(1984);val count=input.read(response)
                        val expected=packet.copyOf();packet.copyInto(expected,12,16,20);packet.copyInto(expected,16,12,16)
                        check(count==expected.size&&response.copyOf(count).contentEquals(expected)){"Original IP/fragment payload changed"}
                    }
                }
            }
            held.close();owned=null
            check(child.waitFor(2,TimeUnit.SECONDS)&&child.exitValue()==0){"Owned Go child failed: ${stdout.readText().take(512)}"}
            owner.awaitDeath();timeout.cancel(false)
        }finally {
            owned?.close();control.close();stranger.close()
            process?.let {child->if(child.isAlive){child.destroy();if(!child.waitFor(1,TimeUnit.SECONDS)){child.destroyForcibly();check(child.waitFor(2,TimeUnit.SECONDS))}}}
            watchdog.shutdownNow()
        }
    }
    private fun cancelAccept(executable:File,directory:File) {
        val owner=CarrierProcessGroup(executable,directory)
        val control=CarrierOriginalPacketControl(owner,38){false}
        val began=CountDownLatch(1);val ended=CountDownLatch(1);val error=AtomicReference<Throwable?>()
        val thread=Thread {
            began.countDown()
            try{control.await(SystemClock.elapsedRealtime()+5_000)}catch(failure:Throwable){error.set(failure)}finally{ended.countDown()}
        }
        thread.start();check(began.await(1,TimeUnit.SECONDS));Thread.sleep(150);control.close()
        check(ended.await(1,TimeUnit.SECONDS)&&error.get()!=null){"Cancelled listener did not retire"}
        LocalServerSocket("mcrf.carrier.${owner.nonce}").close()
    }
}
