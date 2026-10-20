package org.mcrf.client

import android.app.Instrumentation
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.function.*

/** Installed DNSTT client + checksum-pinned upstream Android server, only localhost.
 * No main libbox, TUN, saved profile, public resolver, account or remote VPS. */
object DnsttLoopbackDeviceChecks {
    private fun full(input:InputStream,n:Int):ByteArray {val bytes=ByteArray(n);var at=0;while(at<n){val got=input.read(bytes,at,n-at);if(got<0)throw EOFException("DNSTT fixture response ended early");check(got>0);at+=got};return bytes}
    private fun line(input:InputStream):String {val out=ByteArrayOutputStream();while(out.size()<8192){val b=input.read();check(b>=0);if(b==10)return out.toString("US-ASCII").trimEnd('\r');out.write(b)};error("Fixture header too long")}
    @JvmStatic fun run(test:Instrumentation,temporary:File,passed:Consumer<String>){
        check(BuildConfig.DEBUG)
        val context=test.targetContext;val before=ProfileStore.snapshot(context)
        val cacheBefore=context.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("mcrf-carrier-")}.map {it.name}.toSet()
        val usage=requireNotNull(EngineUpdateGate.acquireUsage(context)){"Engine mutation active"}
        usage.use {
            NativeCarrier.executable(context,"dnstt")
            val binary=File(test.context.applicationInfo.nativeLibraryDir,"libdnstt_testserver.so")
            require(binary.isFile&&binary.canExecute()){"Build with explicit DNSTT test-only peer stage"}
            java.util.zip.ZipFile(context.applicationInfo.sourceDir).use {apk->check(apk.entries().asSequence().none {it.name.startsWith("lib/")&&it.name.endsWith(".so")}){"Base APK must remain native-free"}}
            val privateKey=File(temporary,"server.key");val publicKey=File(temporary,"server.pub")
            val key=ProcessBuilder(binary.path,"-gen-key","-privkey-file",privateKey.path,"-pubkey-file",publicKey.path).redirectErrorStream(true).start()
            try {check(key.waitFor(5,TimeUnit.SECONDS)&&key.exitValue()==0){"Owned DNSTT key generation failed"}}finally{if(key.isAlive){key.destroyForcibly();key.waitFor(5,TimeUnit.SECONDS)};key.inputStream.close()}
            android.system.Os.chmod(privateKey.path,0x180);android.system.Os.chmod(publicKey.path,0x180)
            val pub=publicKey.readText(Charsets.US_ASCII).trim();require(pub.matches(Regex("[0-9a-fA-F]{64}")))
            passed.accept("Installed signed DNSTT client and test-only upstream server available; fresh private Noise key pair generated")
            val udp=DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use {it.localPort}
            val executor=Executors.newCachedThreadPool();val running=AtomicBoolean(true);val requests=AtomicInteger()
            val sockets=ConcurrentHashMap.newKeySet<Socket>();val payload=ByteArray(8192){(it%251).toByte()};val nonce=UUID.randomUUID().toString()
            try {ServerSocket(0,8,InetAddress.getByName("127.0.0.1")).use {origin->
                executor.submit {while(running.get())try{
                    val socket=origin.accept();sockets.add(socket)
                    executor.submit {try{socket.use {
                        socket.soTimeout=5000;val input=BufferedInputStream(socket.getInputStream());val out=socket.getOutputStream()
                        check(full(input,3).contentEquals(byteArrayOf(5,1,0)));out.write(byteArrayOf(5,0));out.flush()
                        check(full(input,10).contentEquals(byteArrayOf(5,1,0,1,127,0,0,1,0,80)))
                        out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,1));out.flush()
                        check(line(input)=="GET /$nonce HTTP/1.1");while(line(input).isNotEmpty()){}
                        requests.incrementAndGet();out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));out.write(payload);out.flush()
                    }}catch(_:EOFException){}catch(_:IOException){}finally{sockets.remove(socket)}}
                }catch(_:SocketException){break}}
                val upstream=ProcessBuilder(binary.path,"-udp","127.0.0.1:$udp","-privkey-file",privateKey.path,"t.mcrf.invalid","127.0.0.1:${origin.localPort}").redirectErrorStream(true).start()
                val drain=Thread({try{upstream.inputStream.use {stream->val bytes=ByteArray(4096);while(stream.read(bytes)>=0){}}}catch(_:IOException){}},"mcrf-dnstt-owned-server-output").apply {isDaemon=true;start()}
                try {
                    Thread.sleep(200);check(upstream.isAlive){"Owned DNSTT UDP server did not start"}
                    val profile=JSONObject().put("type","dnstt").put("domain","t.mcrf.invalid").put("key",pub).put("udp","127.0.0.1:$udp")
                    repeat(2){attempt->
                        var port=0
                        NativeCarrier.start(context,profile,BooleanSupplier {false},15000).use {carrier->
                            port=carrier.port
                            fun auth(socket:Socket,password:String){val out=socket.getOutputStream();val input=socket.getInputStream();out.write(byteArrayOf(5,1,2));out.flush();check(full(input,2).contentEquals(byteArrayOf(5,2)))
                                val user=carrier.username.toByteArray();val pass=password.toByteArray();out.write(byteArrayOf(1,user.size.toByte())+user+byteArrayOf(pass.size.toByte())+pass);out.flush()}
                            try{Socket("127.0.0.1",port).use {socket->socket.soTimeout=3000;socket.getOutputStream().write(byteArrayOf(5,1,0));check(full(socket.getInputStream(),2).contentEquals(byteArrayOf(5,0xff.toByte())))}}catch(error:SocketTimeoutException){throw IllegalStateException("DNSTT no-auth refusal timed out",error)}catch(_:IOException){}
                            try{Socket("127.0.0.1",port).use {socket->socket.soTimeout=3000;auth(socket,"wrong-${UUID.randomUUID()}");check(!full(socket.getInputStream(),2).contentEquals(byteArrayOf(1,0))){"Wrong DNSTT bridge credentials accepted"}}}catch(error:SocketTimeoutException){throw IllegalStateException("DNSTT wrong-password refusal timed out",error)}catch(_:IOException){}
                            passed.accept("DNSTT run${attempt+1} loopback refuses missing and incorrect session authentication")
                            Socket("127.0.0.1",port).use {socket->
                                socket.soTimeout=15000;auth(socket,carrier.password);val input=BufferedInputStream(socket.getInputStream());val out=socket.getOutputStream()
                                check(full(input,2).contentEquals(byteArrayOf(1,0)));out.write(byteArrayOf(5,1,0,1,127,0,0,1,0,80));out.flush()
                                val reply=full(input,4);check(reply[0].toInt()==5&&reply[1].toInt()==0)
                                when(reply[3].toInt()){1->full(input,6);4->full(input,18);3->{val n=input.read();check(n>=0);full(input,n+2)};else->error("Invalid tunneled SOCKS address")}
                                out.write("GET /$nonce HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));out.flush()
                                check(line(input)=="HTTP/1.1 200 OK");var length=-1
                                while(true){val header=line(input);if(header.isEmpty())break;if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()}
                                check(length==payload.size&&full(input,length).contentEquals(payload)&&requests.get()==attempt+1){"Real encrypted DNS tunnel payload mismatch"}
                            }
                            passed.accept("DNSTT run${attempt+1} carries exact8192-byte HTTP response through real Noise/DNS server and remote SOCKS endpoint")
                        }
                        var closed=false;try{Socket().use {it.connect(InetSocketAddress("127.0.0.1",port),500)}}catch(_:IOException){closed=true};check(closed){"Owned DNSTT port survived close"}
                        passed.accept("DNSTT run${attempt+1} STOP completes and owned bridge port is closed before reconnect")
                    }
                }finally{
                    running.set(false);if(upstream.isAlive)upstream.destroy();if(!upstream.waitFor(5,TimeUnit.SECONDS)){upstream.destroyForcibly();check(upstream.waitFor(5,TimeUnit.SECONDS))};check(!upstream.isAlive);drain.join(3000)
                }
            }}finally{
                running.set(false);sockets.forEach {try{it.close()}catch(_:IOException){}};executor.shutdownNow();check(executor.awaitTermination(5,TimeUnit.SECONDS))
                check(privateKey.delete()||!privateKey.exists());check(publicKey.delete()||!publicKey.exists())
                check(ProfileStore.snapshot(context)==before){"DNSTT fixture changed user data"}
                check(context.cacheDir.listFiles().orEmpty().filter {it.name.startsWith("mcrf-carrier-")}.map {it.name}.toSet()==cacheBefore){"Owned DNSTT runtime files remain"}
            }
            passed.accept("Owned DNSTT peers/keys/runtime files cleaned; user profiles/settings unchanged; no Internet/TUN/account actions")
        }
    }
}
