package org.mcrf.client

import android.content.Context
import android.system.Os
import org.json.JSONObject
import org.json.JSONArray
import org.mcrf.engine.spi.v1.Engine
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Real installed-module gates. Disposable loopback traffic only; no saved profile or choice writes. */
object ClientPrimaryModuleDeviceChecks {
    @JvmStatic fun run(context:Context,passed:Consumer<String>) {
        fun check(ok:Boolean,label:String){if(!ok)throw IllegalStateException(label);passed.accept(label)}
        listOf("go.Seq","io.nekohasekai.libbox.Libbox","org.steer.core.SteerCore").forEach {name->
            var absent=false;try {context.classLoader.loadClass(name)}catch(_:ClassNotFoundException){absent=true}
            check(absent,"Base does not define $name")
        }
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use {zip->check(zip.entries().asSequence().none {it.name.startsWith("lib/")},"Base APK contains no bundled native engine")}
        ClientPrimaryPlatformBinderTest.run(passed)
        val session=ClientPrimaryWorker.connect(context,"singbox",System.nanoTime().coerceAtLeast(1),null)
        val pid=session.remotePid
        try {
            val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {it.localPort}
            val config=JSONObject().put("outbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","fixture-direct")))
                .put("inbounds",JSONArray().put(JSONObject().put("type","mixed").put("tag","fixture-in").put("listen","127.0.0.1").put("listen_port",port)))
                .put("route",JSONObject().put("final","fixture-direct"))
            check(session.validate("",ClientPrimarySelection.envelope(config,false,false),Engine.Mode.PROXY).supported,"Installed sing-box loads JNI and accepts actual full native config")
            val invalid=JSONObject(config.toString()).put("inbounds",JSONArray().put(JSONObject().put("type","not-a-real-native-inbound")))
            check(!session.validate("",ClientPrimarySelection.envelope(invalid,false,false),Engine.Mode.PROXY).supported,"Installed native validator rejects invalid configuration")
            val unexpected=EngineUpdateGate.beginMutation(context,"primary_fixture","flux","openflux")
            if(unexpected!=null)try {EngineUpdateGate.complete(context,unexpected.pending.token){true}}finally{unexpected.close()}
            check(unexpected==null,"Live installed-primary worker holds cross-process mutation barrier")
        }finally{session.close()}
        val alive=try {Os.stat("/proc/$pid");true}catch(_:Exception){false}
        check(!alive,"Preflight close observes worker process death before returning")
        ClientPrimarySteerDeviceChecks.run(context,passed)
        Loopback().use {peer->
            val node=ServerImport.Server("fixture:primary-direct","Primary module loopback","direct","127.0.0.1",0,
                JSONObject().put("type","direct").put("tag","fixture-direct"),null,null)
            val measured=ProbeCoordinator.measure(context,listOf(node),peer.url)
            check(measured.keys==setOf(node.id)&&measured.getValue(node.id)>=0&&peer.received.await(2,TimeUnit.SECONDS),"Actual HTTP traverses newly installed sing-box worker and returns latency [${EngineUpdateOpenFluxDiagnostics.probeCategory(ProbeCoordinator.diagnostic)}]")
            ProbeCoordinator.awaitQuiescent(context)
            val lease=EngineUpdateGate.acquireUsage(context)
            check(lease!=null,"Native probe cleanup acknowledges quiescence")
            lease?.close()
            val repeated=ProbeCoordinator.measure(context,listOf(node),peer.url)
            check(repeated.getValue(node.id)>=0,"Second probe starts a fresh working core process")
        }
    }
    private class Loopback:AutoCloseable {
        // Exercise the real physical-network binding against our own on-device origin;
        // URLTestOutbound uses this exact outbound, not the router's localhost bypass.
        private val address=java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter {it.isUp&&!it.isLoopback&&!it.name.startsWith("tun")}
            .flatMap {it.inetAddresses.toList()}.filterIsInstance<java.net.Inet4Address>()
            .first { !it.isLoopbackAddress&&!it.isLinkLocalAddress }
        private val server=ServerSocket(0,4,address)
        private val workers=Executors.newSingleThreadExecutor()
        val received=CountDownLatch(1)
        val url="http://${address.hostAddress}:${server.localPort}/primary-fixture"
        init {workers.execute {while(!server.isClosed)try {server.accept().use {socket->
            socket.soTimeout=3000
            val reader=socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val first=reader.readLine();var total=first?.length?:0
            while(true){val line=reader.readLine()?:break;total+=line.length;require(total<8192);if(line.isEmpty())break}
            // sing-box URLTest sends HEAD, not GET. A fixture must answer the actual
            // protocol before a failed test can be attributed to the installed module.
            if(first in setOf("HEAD /primary-fixture HTTP/1.1","GET /primary-fixture HTTP/1.1")){received.countDown();socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));socket.getOutputStream().flush()}
        }}catch(_:Exception){if(server.isClosed)break}}}
        override fun close(){server.close();workers.shutdownNow();workers.awaitTermination(4,TimeUnit.SECONDS)}
    }
}
