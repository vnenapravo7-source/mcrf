package org.mcrf.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Isolated fixture processes/sockets; never replace the user's profile. */
object OpenFluxDeviceChecks {
    @JvmStatic fun run(context: Context, temporary: File, passed: Consumer<String>) {
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        val binary = NativeCarrier.executable(context, "openflux")
        val exitBinary = File(binary.parentFile, "libopenflux_testserver.so")
        verify(exitBinary.canExecute(), "OpenFlux fixture is a separately installed debug executable")
        val loopback = InetAddress.getByName("127.0.0.1")
        // The upstream gVisor client treats 127/8 as its own loopback, not an
        // exit destination. Its own end-to-end tests use a LAN address too.
        // Bind just one real interface, never 0.0.0.0; expose no user data.
        val originAddress = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: error("The OpenFlux traffic fixture needs an active IPv4 interface")
        val requestPath = "/mcrf-openflux-fixture-${UUID.randomUUID()}"
        val worker = Executors.newCachedThreadPool()
        val count = AtomicInteger()
        ServerSocket(0, 8, originAddress).use { origin ->
            origin.soTimeout = 15000
            val response = worker.submit {
                repeat(2){origin.accept().use { socket ->
                    socket.soTimeout = 12000; val request = java.io.ByteArrayOutputStream()
                    while (request.size() < 8192) { val value = socket.getInputStream().read(); check(value >= 0); request.write(value)
                        if (request.toString("US-ASCII").endsWith("\r\n\r\n")) break }
                    val line=request.toString("US-ASCII");check(line.contains(" $requestPath ") && (line.startsWith("GET ") || line.startsWith("HEAD "))); count.incrementAndGet()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 8192\r\nConnection: close\r\n\r\n"+"M".repeat(8192)).toByteArray(Charsets.US_ASCII))
                }}
            }
            val exitPort = ServerSocket(0, 1, loopback).use { it.localPort }
            val secret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            val server = File(temporary, "openflux-exit.json").apply { writeText(JSONObject().put("Listen", "127.0.0.1:$exitPort").put("Secret", secret).put("Context", "http://mcrf-openflux-fixture").toString()) }
            android.system.Os.chmod(server.absolutePath, 0x180)
            val upstream = ProcessBuilder(exitBinary.absolutePath, "--config", server.absolutePath).directory(temporary).redirectErrorStream(true).start()
            try {
                worker.submit { upstream.inputStream.use { input -> val bytes=ByteArray(4096); while(input.read(bytes)>=0){} } }
                val profile = JSONObject().put("type", "openflux").put("name", "OpenFlux isolated fixture").put("negotiate", true).put("secret", secret)
                    .put("context", "http://mcrf-openflux-fixture").put("codec", "batched")
                    .put("transports", JSONArray().put(JSONObject().put("type", "direct").put("name", "fixture-direct").put("dial", "127.0.0.1:$exitPort")))
                // Exit uses the same named transport as the exported profile.
                profile.getJSONArray("transports").getJSONObject(0).put("name", "direct")
                val imported = ServerImport.read(OpenFluxProfile.share(profile)).single()
                verify(imported.carrier!!.getString("secret") == secret, "Standard OpenFlux link retains the encryption key")
                Thread.sleep(300)
                NativeCarrier.start(context, imported.carrier, BooleanSupplier { false }, 15000).use { carrier ->
                    verify(NativeCarrier.socksReady(carrier.port, carrier.username, carrier.password), "OpenFlux reports an encrypted connection and authenticated SOCKS5 readiness")
                    Socket("127.0.0.1", carrier.port).use { socket ->
                        socket.soTimeout = 12000; val input=socket.getInputStream();val out=socket.getOutputStream()
                        out.write(byteArrayOf(5,1,2));check(input.read()==5&&input.read()==2)
                        val user=carrier.username.toByteArray();val pass=carrier.password.toByteArray()
                        out.write(1);out.write(user.size);out.write(user);out.write(pass.size);out.write(pass);check(input.read()==1&&input.read()==0)
                        out.write(byteArrayOf(5,1,0,1) + originAddress.address + byteArrayOf((origin.localPort ushr 8).toByte(),origin.localPort.toByte()))
                        val header=ByteArray(10);java.io.DataInputStream(input).readFully(header)
                        check(header[1].toInt()==0) { "OpenFlux SOCKS CONNECT failed with reply ${header[1].toInt() and 255}" }
                        passed.accept("SOCKS CONNECT reaches the isolated origin through OpenFlux")
                        out.write("GET $requestPath HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        val payload=ImportIO.read(input).toString(Charsets.US_ASCII)
                        verify(payload.contains("200 OK")&&payload.endsWith("M".repeat(8192)),"8192-byte HTTP response traverses the real encrypted OpenFlux tunnel")
                    }
                    verify(count.get()==1,"Only the intended fixture origin receives the request")
                }
                verify(context.cacheDir.listFiles()?.none{it.name.startsWith("mcrf-carrier-")}==true,"OpenFlux private runtime removed after STOP")
                val before=ProfileStore.snapshot(context)
                val measured=ProbeCoordinator.measure(context,listOf(imported),"http://${originAddress.hostAddress}:${origin.localPort}$requestPath")
                verify(measured[imported.id]?.let { it>=0 }==true,"Isolated libbox URL test passes through a newly owned native OpenFlux carrier (${ProbeCoordinator.diagnostic}; fixture requests=${count.get()})")
                response.get(5,TimeUnit.SECONDS)
                verify(count.get()==2,"Native probe reaches the intended fixture origin exactly once")
                verify(ProfileStore.snapshot(context)==before,"Native probe does not replace the user's profile or catalogue")
                val wrong=JSONObject(imported.carrier.toString()).put("secret","Wrong isolated encryption fixture")
                var rejected=false
                try { NativeCarrier.start(context,wrong,BooleanSupplier { false },3000).close() } catch(_:Exception){rejected=true}
                verify(rejected,"Wrong encryption key cannot become connection-ready")
            } finally {
                try { upstream.outputStream.write("STOP\n".toByteArray());upstream.outputStream.flush();upstream.outputStream.close() } catch(_:Exception){}
                if(!upstream.waitFor(5,TimeUnit.SECONDS)){upstream.destroyForcibly();upstream.waitFor(3,TimeUnit.SECONDS)}
                server.delete();worker.shutdownNow()
            }
        }
        verify(context.cacheDir.listFiles()?.none{it.name.startsWith("mcrf-carrier-")}==true,"Failed OpenFlux startup also cleans its private runtime")
    }
}
