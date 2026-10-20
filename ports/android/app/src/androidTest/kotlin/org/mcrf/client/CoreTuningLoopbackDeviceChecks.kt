package org.mcrf.client

import android.app.Instrumentation
import android.os.SystemClock
import android.system.Os
import io.nekohasekai.libbox.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Actual native listeners on ephemeral loopback ports. Coordinator must quiesce VPN/probes first.
 * No saved settings, profile generation, Android clock, VPN builder or external network is touched. */
object CoreTuningLoopbackDeviceChecks {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private fun port(used: MutableSet<Int>): Int {
        repeat(20) { val candidate = ServerSocket(0,1,loopback).use { it.localPort }; if(used.add(candidate)) return candidate }
        error("Cannot select separate fixture ports")
    }
    private fun base() = JSONObject().put("log",JSONObject().put("disabled",true)).put("inbounds",JSONArray())
        .put("outbounds",JSONArray().put(JSONObject().put("type","direct").put("tag","direct")))
        .put("route",JSONObject().put("final","direct").put("auto_detect_interface",false))
    private fun native(instrumentation: Instrumentation, config: JSONObject, run: () -> Unit) {
        check(!McrfVpnService.status.startsWith("VPN подключён") && !McrfVpnService.status.startsWith("Прокси ") && McrfVpnService.status != "Подключение…") { "Coordinator must quiesce user VPN/probes before fixture" }
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir,"CoreTuning-fixture-${UUID.randomUUID()}").canonicalFile
        check(directory.parentFile == context.cacheDir.canonicalFile && directory.mkdir())
        Os.chmod(directory.absolutePath,0x1c0)
        var server: CommandServer? = null
        try {
            go.Seq.setContext(context.applicationContext)
            val used=mutableSetOf<Int>()
            config.optJSONObject("experimental")?.optJSONObject("clash_api")?.optString("external_controller")?.substringAfterLast(':')?.toIntOrNull()?.let(used::add)
            listOf("inbounds","services").forEach { key -> config.optJSONArray(key)?.let { array -> repeat(array.length()) { index -> array.getJSONObject(index).optInt("listen_port").takeIf { it>0 }?.let(used::add) } } }
            val command = port(used)
            Libbox.setup(SetupOptions().apply { setBasePath(directory.absolutePath);setWorkingPath(directory.absolutePath);setTempPath(directory.absolutePath)
                setFixAndroidStack(true);setLogMaxLines(0);setCommandServerListenPort(command);setCommandServerSecret(UUID.randomUUID().toString()) })
            val callbacks = java.lang.reflect.InvocationHandler { _,method,_ -> when(method.name) {
                "openTun" -> error("Loopback fixture must never create TUN")
                "getInterfaces" -> object: NetworkInterfaceIterator { override fun hasNext()=false; override fun next(): io.nekohasekai.libbox.NetworkInterface?=null }
                "getSystemProxyStatus" -> SystemProxyStatus()
                "readWIFIState" -> Libbox.newWIFIState("","")
                else -> when(method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; String::class.java -> ""; else -> null }
            } }
            val handler = Proxy.newProxyInstance(javaClass.classLoader,arrayOf<Class<*>>(CommandServerHandler::class.java),callbacks) as CommandServerHandler
            val platform = Proxy.newProxyInstance(javaClass.classLoader,arrayOf<Class<*>>(PlatformInterface::class.java),callbacks) as PlatformInterface
            Libbox.checkConfig(config.toString())
            val started = Libbox.newCommandServer(handler,platform);server=started
            started.start();started.startOrReloadService(config.toString(),OverrideOptions())
            try { run() } finally { try { started.closeService() } finally { started.close();server=null } }
            assertClosed(command)
        } finally {
            server?.let { try { it.closeService() } finally { it.close() } }
            clean(directory,directory)
        }
    }
    private fun clean(root: File,item: File) {
        val canonical=item.canonicalFile
        check(root.name.matches(Regex("CoreTuning-fixture-[0-9a-f-]{36}")) && (canonical==root || canonical.path.startsWith(root.path+File.separator)))
        check(!Files.isSymbolicLink(item.toPath()))
        if(item.isDirectory) (item.listFiles() ?: error("Cannot read fixture directory")).forEach { clean(root,it) }
        check(!item.exists() || item.delete()) { "Cannot clean owned fixture file" }
    }
    private data class Http(val code: Int,val headers: Map<String,String>,val body: ByteArray)
    private fun http(port: Int,path: String,method: String="GET",secret: String?=null,origin: String?=null,grpc: Boolean=false): Http {
        val connection=URL("http://127.0.0.1:$port$path").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout=3000;connection.readTimeout=5000;connection.requestMethod=method;connection.instanceFollowRedirects=false;connection.useCaches=false
        connection.setRequestProperty("Connection","close")
        secret?.let { connection.setRequestProperty("Authorization","Bearer $it") }
        origin?.let { connection.setRequestProperty("Origin",it) }
        if(method=="OPTIONS") { connection.setRequestProperty("Access-Control-Request-Method",if(grpc)"POST" else "GET");connection.setRequestProperty("Access-Control-Request-Headers",if(grpc)"authorization,content-type,x-grpc-web" else "authorization") }
        if(grpc && method=="POST") { connection.setRequestProperty("Content-Type","application/grpc-web+proto");connection.setRequestProperty("X-Grpc-Web","1");connection.doOutput=true;connection.setFixedLengthStreamingMode(5) }
        try {
            if(grpc && method=="POST") connection.outputStream.use { it.write(ByteArray(5)) } // empty protobuf request, one uncompressed gRPC data frame
            val code=connection.responseCode
            val stream=if(code>=400)connection.errorStream else connection.inputStream
            val body=stream?.use { input -> val out=java.io.ByteArrayOutputStream();val bytes=ByteArray(1024)
                while(true) { val count=input.read(bytes);if(count<0)break;check(out.size()+count<=65536);out.write(bytes,0,count) };out.toByteArray() } ?: ByteArray(0)
            val headers=connection.headerFields.filterKeys { it!=null }.mapKeys { it.key!!.lowercase() }.mapValues { it.value.joinToString(",") }
            return Http(code,headers,body)
        } finally { connection.disconnect() }
    }
    private fun diagnostic(result: Http): String {
        val frames=mutableListOf<String>();var cursor=0
        while(cursor+5<=result.body.size && frames.size<8) {
            val flags=result.body[cursor].toInt() and 255
            val size=java.nio.ByteBuffer.wrap(result.body,cursor+1,4).int
            frames.add("$flags:$size")
            if(size<0 || size>result.body.size-cursor-5)break
            cursor+=5+size
        }
        val status=result.headers["grpc-status"]?.toIntOrNull()?.toString() ?: "absent-or-invalid"
        val type=result.headers["content-type"].orEmpty().take(80).filterNot(Char::isISOControl)
        // This diagnostic is used only for fixture GetVersion RPC responses. The
        // bounded prefix describes framing/version or fixed auth-error metadata;
        // never expose request headers, tokens, full payload or grpc-message text.
        val prefix=result.body.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "HTTP=${result.code},contentType=$type,bodyBytes=${result.body.size},statusHeader=$status,hexPrefix=$prefix,frames=${frames.joinToString(";")},parsedBytes=$cursor"
    }
    private fun grpcStatus(result: Http): Pair<Int,Boolean> {
        val description=diagnostic(result)
        check(result.code==200) { "gRPC-Web HTTP response: $description" }
        check(result.headers["content-type"].orEmpty().startsWith("application/grpc-web")) { "Unexpected RPC content type: $description" }
        val headerStatus=result.headers["grpc-status"]?.let { text -> text.toIntOrNull().also { check(it!=null) { "Invalid RPC status header: $description" } } }
        var index=0;var status:Int?=null;var data=false
        while(index+5<=result.body.size) {
            val flags=result.body[index].toInt() and 255
            val size=java.nio.ByteBuffer.wrap(result.body,index+1,4).int
            check(size>=0 && size<=result.body.size-index-5 && flags in setOf(0,128)) { "Invalid gRPC-Web response frame: $description" }
            val start=index+5
            if((flags and 128)!=0) {
                val trailer=String(result.body,start,size,Charsets.US_ASCII)
                val parsed=trailer.lineSequence().firstOrNull { it.substringBefore(':').equals("grpc-status",true) }?.substringAfter(':')?.trim()?.toIntOrNull()
                check(parsed!=null && (status==null || status==parsed)) { "Invalid or conflicting RPC trailer: $description" }
                status=parsed
            } else if(size>0) data=true
            index=start+size
        }
        check(index==result.body.size) { "Truncated RPC framing: $description" }
        // Native webResponseWriter.finish() emits trailers-only RPC errors as HTTP200
        // headers with an empty body when no data/header write occurred. This is an
        // explicit branch in pinned service/api/web_bridge.go, not a missing trailer.
        if(status==null) {
            check(result.body.isEmpty() && headerStatus!=null) { "Missing RPC status in headers/trailer: $description" }
            status=headerStatus
        } else if(headerStatus!=null) check(status==headerStatus) { "RPC header/trailer status mismatch: $description" }
        return status!! to data
    }
    private fun requireDenied(result: Http) {
        // HTTP401 is an explicit HTTP authentication rejection; all other HTTP
        // failures (404/5xx/etc.) are not accepted as proof of secret enforcement.
        if(result.code==401)return
        val parsed=grpcStatus(result)
        check(parsed.first==16 && !parsed.second) { "Expected auth rejection without RPC data: ${diagnostic(result)}" }
    }
    private fun assertClosed(port: Int) {
        val deadline=SystemClock.elapsedRealtime()+3000
        do {
            val open=try { Socket().use { it.connect(InetSocketAddress(loopback,port),250) };true } catch(_: java.io.IOException) { false }
            if(!open)return
            Thread.sleep(50)
        } while(SystemClock.elapsedRealtime()<deadline)
        error("Native fixture listener remains open after stop: $port")
    }
    @JvmStatic fun api(instrumentation: Instrumentation,passed: Consumer<String>) {
        val used=mutableSetOf<Int>();val clash=port(used);val api=port(used);val secret=UUID.randomUUID().toString()
        val config=CoreTuningConfig.apply(base(),CoreTuningSettings(clashEnabled=true,clashPort=clash,clashSecret=secret,apiEnabled=true,apiPort=api,apiSecret=secret))
        native(instrumentation,config) {
            check(http(clash,"/version").code==401);check(http(clash,"/version",secret="wrong-fixture-secret").code==401)
            val clashGood=http(clash,"/version",secret=secret)
            check(clashGood.code==200 && JSONObject(String(clashGood.body,Charsets.UTF_8)).optString("version").isNotBlank())
            passed.accept("Actual native Clash /version denies missing/wrong secret and accepts correct secret")
            val rpc="/daemon.StartedService/GetVersion"
            val authenticated=http(api,rpc,"POST",secret=secret,grpc=true)
            val good=grpcStatus(authenticated);check(good.first==0 && good.second) { "Correct secret failed GetVersion: ${diagnostic(authenticated)}" }
            passed.accept("Actual native sing-box gRPC-Web GetVersion returns data for correct secret (${diagnostic(authenticated)})")
            val missing=http(api,rpc,"POST",grpc=true);requireDenied(missing)
            val wrong=http(api,rpc,"POST",secret="wrong-fixture-secret",grpc=true);requireDenied(wrong)
            passed.accept("Native sing-box denies missing/wrong secret without RPC data (missing:${diagnostic(missing)}; wrong:${diagnostic(wrong)})")
            listOf(clash to "/version",api to rpc).forEach { (port,path) ->
                val origin="http://127.0.0.1:$port"
                val allowed=http(port,path,"OPTIONS",origin=origin,grpc=port==api)
                check(allowed.headers["access-control-allow-origin"]==origin) { "Allowed API origin missing" }
                val denied=http(port,path,"OPTIONS",origin="https://fixture.invalid",grpc=port==api)
                check(denied.headers["access-control-allow-origin"].isNullOrEmpty()) { "Foreign browser origin granted API CORS" }
            }
            passed.accept("Actual Clash and sing-box API CORS grant only configured same-port loopback origin; foreign origin not granted")
            val selfAddresses=java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
                .flatMap { java.util.Collections.list(it.inetAddresses) }.filter { it is Inet4Address && !it.isLoopbackAddress }.take(3)
            selfAddresses.forEach { address -> listOf(clash,api).forEach { port ->
                val reachable=try {Socket().use {it.connect(InetSocketAddress(address,port),300)};true}catch(_:java.io.IOException){false}
                check(!reachable) { "Native control API reachable through nonloopback phone address" }
            } }
            if(selfAddresses.isNotEmpty())passed.accept("Both API ports reject connections to the phone's own nonloopback IPv4 addresses")
        }
        assertClosed(clash);assertClosed(api)
        passed.accept("STOP closes both native API listeners and private command endpoint; no user settings or TUN touched")
    }
    @JvmStatic fun ntp(instrumentation: Instrumentation,passed: Consumer<String>) {
        val running=AtomicBoolean(true);val observed=CountDownLatch(1);val failure=AtomicReference<Throwable?>()
        DatagramSocket(0,loopback).use { responder ->
            responder.soTimeout=250
            val worker=Thread({ try {
                while(running.get()) {
                    val packet=DatagramPacket(ByteArray(512),512)
                    try { responder.receive(packet) } catch(_: SocketTimeoutException) { continue }
                    check(packet.length>=48 && packet.address.isLoopbackAddress && (packet.data[0].toInt() and 7)==3)
                    val reply=ByteArray(48);reply[0]=((packet.data[0].toInt() and 56) or 4).toByte();reply[1]=1;reply[2]=4;reply[3]=(-20).toByte()
                    packet.data.copyInto(reply,24,40,48)
                    val milliseconds=System.currentTimeMillis()+3_600_000L
                    val seconds=milliseconds/1000+2_208_988_800L;val fraction=(milliseconds%1000)*4_294_967_296L/1000
                    listOf(16,32,40).forEach { offset -> java.nio.ByteBuffer.wrap(reply,offset,8).putInt(seconds.toInt()).putInt(fraction.toInt()) }
                    responder.send(DatagramPacket(reply,reply.size,packet.address,packet.port));observed.countDown()
                }
            } catch(error: Throwable) { if(running.get())failure.set(error) } },"CoreTuning-NTP-fixture").apply { isDaemon=true;start() }
            val wall=System.currentTimeMillis();val monotonic=SystemClock.elapsedRealtime()
            try {
                val config=CoreTuningConfig.apply(base(),CoreTuningSettings(ntpEnabled=true,ntpServer="127.0.0.1",ntpPort=responder.localPort,ntpInterval="1s"))
                check(!config.getJSONObject("ntp").getBoolean("write_to_system"))
                native(instrumentation,config) {
                    check(observed.await(8,TimeUnit.SECONDS)) { "No actual loopback native NTP query: ${failure.get()?.javaClass?.simpleName}" }
                    check(failure.get()==null)
                    check(kotlin.math.abs((System.currentTimeMillis()-wall)-(SystemClock.elapsedRealtime()-monotonic))<3000) { "Android wall clock changed by fixture NTP offset" }
                    passed.accept("Native NTP exchanges a real localhost UDP request/reply with one-hour fixture offset; Android wall clock remains unchanged")
                }
            } finally { running.set(false);responder.close();worker.join(2000);check(!worker.isAlive) }
        }
        passed.accept("Native NTP core stopped and isolated UDP fixture cleaned; no system-clock write or user preferences")
    }
    /** Creates a fixture-only CA/key entirely in RAM with standard Android/JCA APIs.
     * No AndroidKeyStore aliases, hidden certificate builders or persisted credentials. */
    private fun fixtureCertificate(): Pair<java.security.KeyPair,java.security.cert.X509Certificate> {
        fun concat(vararg pieces: ByteArray):ByteArray {val out=java.io.ByteArrayOutputStream();pieces.forEach(out::write);return out.toByteArray()}
        fun der(tag: Int,content: ByteArray):ByteArray {
            val size=content.size;val length=if(size<128)byteArrayOf(size.toByte()) else {
                val bytes=java.math.BigInteger.valueOf(size.toLong()).toByteArray().dropWhile {it==0.toByte()}.toByteArray()
                byteArrayOf((128 or bytes.size).toByte())+bytes
            }
            return byteArrayOf(tag.toByte())+length+content
        }
        fun sequence(vararg pieces: ByteArray)=der(48,concat(*pieces))
        fun oid(vararg bytes: Int)=der(6,bytes.map(Int::toByte).toByteArray())
        val pair=java.security.KeyPairGenerator.getInstance("RSA").apply {initialize(2048)}.generateKeyPair()
        val algorithm=sequence(oid(42,134,72,134,247,13,1,1,11),der(5,ByteArray(0))) // SHA256withRSA
        val name=javax.security.auth.x500.X500Principal("CN=localhost").encoded
        val formatter=java.text.SimpleDateFormat("yyMMddHHmmss'Z'",java.util.Locale.ROOT).apply {timeZone=java.util.TimeZone.getTimeZone("UTC")}
        val now=System.currentTimeMillis()
        val validity=sequence(der(23,formatter.format(java.util.Date(now-60_000)).toByteArray(Charsets.US_ASCII)),der(23,formatter.format(java.util.Date(now+86_400_000)).toByteArray(Charsets.US_ASCII)))
        val san=sequence(oid(85,29,17),der(4,sequence(der(130,"localhost".toByteArray(Charsets.US_ASCII)),der(135,byteArrayOf(127,0,0,1)))))
        val constraints=sequence(oid(85,29,19),der(1,byteArrayOf((-1).toByte())),der(4,sequence(der(1,byteArrayOf((-1).toByte())))))
        val tbs=sequence(der(160,der(2,byteArrayOf(2))),der(2,byteArrayOf(1)),algorithm,name,validity,name,pair.public.encoded,der(163,sequence(constraints,san)))
        val signature=java.security.Signature.getInstance("SHA256withRSA").apply {initSign(pair.private);update(tbs)}.sign()
        val encoded=sequence(tbs,algorithm,der(3,byteArrayOf(0)+signature))
        val certificate=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as java.security.cert.X509Certificate
        certificate.verify(pair.public);certificate.checkValidity()
        return pair to certificate
    }
    @JvmStatic fun tlsCa(instrumentation: Instrumentation,passed: Consumer<String>) {
        val (keys,certificate)=fixtureCertificate()
        val password=UUID.randomUUID().toString().toCharArray()
        val keyStore=java.security.KeyStore.getInstance("PKCS12").apply {load(null,password);setKeyEntry("fixture",keys.private,password,arrayOf<java.security.cert.Certificate>(certificate))}
        val managers=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply {init(keyStore,password)}
        password.fill('\u0000')
        val tls=javax.net.ssl.SSLContext.getInstance("TLS").apply {init(managers.keyManagers,null,java.security.SecureRandom())}
        val running=AtomicBoolean(true);val successful=java.util.concurrent.atomic.AtomicInteger();val failure=AtomicReference<Throwable?>();val acceptedSocket=AtomicReference<javax.net.ssl.SSLSocket?>()
        val pem="-----BEGIN CERTIFICATE-----\n"+java.util.Base64.getMimeEncoder(64,byteArrayOf(10)).encodeToString(certificate.encoded)+"\n-----END CERTIFICATE-----"
        val server=tls.serverSocketFactory.createServerSocket(0,8,loopback) as javax.net.ssl.SSLServerSocket
        val tlsPort=server.localPort
        server.soTimeout=250
        val worker=Thread({ try {while(running.get()) {
            val socket=try {server.accept() as javax.net.ssl.SSLSocket}catch(_:SocketTimeoutException){continue}
            acceptedSocket.set(socket)
            socket.use { client -> client.soTimeout=6000
                try {client.startHandshake()}catch(_:javax.net.ssl.SSLException){return@use}
                val input=client.inputStream.bufferedReader(Charsets.US_ASCII)
                val first=input.readLine();check(first?.startsWith("CONNECT ")==true) {"Expected real TLS HTTP proxy CONNECT"}
                var line:String?;do {line=input.readLine()}while(line!=null && line.isNotEmpty())
                client.outputStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII));client.outputStream.flush()
                check(input.readLine()?.startsWith("GET /CoreTuning-ca ")==true)
                do {line=input.readLine()}while(line!=null && line.isNotEmpty())
                successful.incrementAndGet()
                client.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nPASS".toByteArray(Charsets.US_ASCII));client.outputStream.flush()
            }
        }}catch(error:Throwable){if(running.get())failure.set(error)} },"CoreTuning-TLS-fixture").apply {isDaemon=true;start()}
        fun verify(store: String,includeCa: Boolean,serverName: String,accept: Boolean) {
            val outbound=JSONObject().put("type","http").put("tag","fixture").put("server","127.0.0.1").put("server_port",tlsPort)
                .put("tls",JSONObject().put("enabled",true).put("server_name",serverName))
            val source=base().put("outbounds",JSONArray().put(outbound)).put("route",JSONObject().put("final","fixture").put("auto_detect_interface",false))
            if(includeCa)source.put("certificate",JSONObject().put("certificate",JSONArray().put(pem)))
            val config=CoreTuningConfig.apply(source,CoreTuningSettings(certificateStore=store,insecure=false))
            native(instrumentation,config) {
                val command=Libbox.newStandaloneCommandClient()
                try {val result=command.urlTestOutbound("fixture","http://127.0.0.1:9/CoreTuning-ca",7000)
                    if(accept)check(result.getError().isEmpty() && result.getDelay()>=0) {"Trusted fixture TLS failed: ${result.getError()}"}
                    else check(result.getError().lowercase().let {"certificate" in it || "x509" in it || "hostname" in it}) {"TLS must fail specifically on certificate verification, not connectivity: ${result.getError()}"}
                } finally {command.disconnect()}
            }
        }
        try {
            verify("system",true,"localhost",true)
            verify("mozilla",true,"localhost",true)
            check(successful.get()==2 && failure.get()==null)
            passed.accept("Real native TLS proxy carries local HTTP traffic with system and Mozilla stores plus explicit fixture CA; insecure stays off")
            verify("mozilla",false,"localhost",false)
            verify("mozilla",true,"wrong.fixture.invalid",false)
            check(successful.get()==2 && failure.get()==null)
            passed.accept("Native TLS refuses untrusted fixture CA and mismatched hostname; failed handshakes never carry HTTP payload")
        } finally {running.set(false);server.close();acceptedSocket.getAndSet(null)?.close();worker.join(3000);check(!worker.isAlive)}
        assertClosed(tlsPort)
        passed.accept("TLS fixture server/native core closed; ephemeral key/certificate stayed in RAM and no user stores changed")
    }
}
