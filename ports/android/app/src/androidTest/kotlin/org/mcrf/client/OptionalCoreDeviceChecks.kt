package org.mcrf.client

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.PortUnreachableException
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Advanced loopback feature checks. Debug test peer is excluded from release modules. */
object OptionalCoreDeviceChecks {
    private class Peer(binary: File,kind: String):AutoCloseable {
        private val child=ProcessBuilder(binary.absolutePath,kind).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        private val events=LinkedBlockingQueue<Pair<String,JSONObject>>()
        init {Thread({try{child.inputStream.bufferedReader().useLines {lines->lines.forEach {line->if(line.length<=32768&&line.startsWith("MCRF_FIXTURE_")){val index=line.indexOf(' ');if(index>0)events.offer(line.substring(0,index) to JSONObject(line.substring(index+1)))}}}}catch(_:Exception){}},"mcrf-test-peer").apply {isDaemon=true;start()}}
        fun await(type: String):JSONObject {val until=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<until){val item=events.poll(100,TimeUnit.MILLISECONDS);if(item?.first==type)return item.second;check(child.isAlive){"Feature peer stopped"}};error("Feature peer event timeout")}
        override fun close(){try{child.outputStream.write("STOP\n".toByteArray());child.outputStream.flush();child.outputStream.close();if(!child.waitFor(3,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(3,TimeUnit.SECONDS)}}catch(_:Exception){child.destroyForcibly()}}
    }
    private fun full(input: java.io.InputStream,count: Int):ByteArray {val result=ByteArray(count);var offset=0;while(offset<count){val read=input.read(result,offset,count-offset);check(read>0){"Fixture response ended"};offset+=read};return result}
    private fun line(input: java.io.InputStream):String {val text=StringBuilder();while(text.length<8192){val b=input.read();check(b>=0);if(b==10)return text.toString().trimEnd('\r');text.append(b.toChar())};error("Fixture header too large")}
    private fun traffic(worker:OptionalCoreProcess,target: String,payload: String) {
        Socket("127.0.0.1",worker.port).use {socket ->
            socket.soTimeout=10000;val input=BufferedInputStream(socket.getInputStream());val output=socket.getOutputStream()
            output.write(byteArrayOf(5,1,2));check(full(input,2).contentEquals(byteArrayOf(5,2)))
            val user=worker.username.toByteArray();val password=worker.password.toByteArray();output.write(byteArrayOf(1,user.size.toByte()));output.write(user);output.write(password.size);output.write(password);check(full(input,2).contentEquals(byteArrayOf(1,0)))
            val port=target.substringAfterLast(':').toInt();output.write(byteArrayOf(5,1,0,1,127,0,0,1,(port shr 8).toByte(),port.toByte()))
            val response=full(input,4);check(response[1].toInt()==0)
            when(response[3].toInt()){1->full(input,6);4->full(input,18);3->{val size=input.read();check(size>=0);full(input,size+2)};else->error("Fixture SOCKS address")}
            output.write("GET / HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII));output.flush()
            check(line(input).contains(" 200 "));var length=-1
            while(true){val header=line(input);if(header.isEmpty())break;if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()}
            check(length==payload.toByteArray().size);check(String(full(input,length),StandardCharsets.UTF_8)==payload){"Feature payload mismatch"}
        }
    }
    private fun udpTraffic(worker:OptionalCoreProcess,target:String) {
        DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use {udp ->
            Socket("127.0.0.1",worker.port).use {control ->
                control.soTimeout=10000
                val input=control.getInputStream();val output=control.getOutputStream()
                output.write(byteArrayOf(5,1,2));check(full(input,2).contentEquals(byteArrayOf(5,2)))
                val user=worker.username.toByteArray();val pass=worker.password.toByteArray()
                output.write(byteArrayOf(1,user.size.toByte())+user+byteArrayOf(pass.size.toByte())+pass)
                check(full(input,2).contentEquals(byteArrayOf(1,0)))
                val port=udp.localPort
                output.write(byteArrayOf(5,3,0,1,127,0,0,1,(port shr 8).toByte(),port.toByte()))
                val reply=full(input,10);check(reply[1].toInt()==0&&reply[3].toInt()==1)
                val relay=InetAddress.getByAddress(reply.copyOfRange(4,8));check(relay.isLoopbackAddress)
                val relayPort=((reply[8].toInt() and 255) shl 8) or (reply[9].toInt() and 255)
                val targetPort=target.substringAfterLast(':').toInt()
                val payload=ByteArray(1152){(it%251).toByte()}
                val packet=byteArrayOf(0,0,0,1,127,0,0,1,(targetPort shr 8).toByte(),targetPort.toByte())+payload
                fun receive(socket:DatagramSocket):ByteArray {val response=DatagramPacket(ByteArray(2048),2048);socket.receive(response);check(response.address==relay&&response.port==relayPort);return response.data.copyOf(response.length)}
                DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use {foreign ->
                    foreign.soTimeout=500;foreign.send(DatagramPacket(packet,packet.size,relay,relayPort))
                    try {receive(foreign);error("Foreign UDP source accepted")}catch(_:SocketTimeoutException){}
                }
                udp.soTimeout=10000;udp.send(DatagramPacket(packet,packet.size,relay,relayPort))
                val echoed=receive(udp);check(echoed.size==packet.size&&echoed.contentEquals(packet)){"Native UDP payload mismatch"}
                control.close()
                // Allow native control-reader cleanup, then prove its relay no longer forwards.
                Thread.sleep(250)
                udp.soTimeout=500;udp.send(DatagramPacket(packet,packet.size,relay,relayPort))
                try {receive(udp);error("UDP relay survived control close")}catch(_:SocketTimeoutException){}catch(_:PortUnreachableException){}
            }
        }
    }
    @JvmStatic fun run(instrumentation: Instrumentation,report: Consumer<String>) {
        val context=instrumentation.targetContext
        val directories=listOf(File(context.applicationInfo.nativeLibraryDir),File(instrumentation.context.applicationInfo.nativeLibraryDir))
        val binary=directories.map {File(it,"liboptional_fixture.so")}.firstOrNull {it.isFile&&it.canExecute()}
        if(binary==null){report.accept("Optional advanced feature checks: SKIP debug-only native peer absent");return}
        val supportsStore="certificate-store" in OptionalCoreProcess.capabilities(context,"tls-core",BooleanSupplier {false})
        if(!supportsStore)report.accept("Optional Mozilla CA feature: SKIP installed TLS module requires new certificate-store capability")
        (listOf("std","chrome")+if(supportsStore)listOf("mozilla") else emptyList()).forEach {fingerprint ->
            Peer(binary,"tls").use {peer ->
                val ready=peer.await("MCRF_FIXTURE_READY");val address=ready.getString("address");val tls=JSONObject().put("enabled",true).put("server_name","fixture.example.test").put("certificate",JSONArray().put(ready.getString("certificate"))).put("tls_tricks",JSONObject().put("mixedcase_sni",true))
                if(fingerprint=="chrome")tls.put("utls",JSONObject().put("enabled",true).put("fingerprint",fingerprint))
                val out=JSONObject().put("type","http").put("server","127.0.0.1").put("server_port",address.substringAfterLast(':').toInt()).put("tls",tls).put("tls_fragment",JSONObject().put("enabled",true).put("size","32-32").put("sleep","20-20"))
                OptionalCoreProcess.start(context,OptionalCoreConfig.Request("tls-core",out,if(fingerprint=="mozilla") "mozilla" else null),BooleanSupplier {false},false,false).use {worker ->
                    traffic(worker,"127.0.0.1:80",ready.getString("payload"));val hello=peer.await("MCRF_FIXTURE_TLS");check(hello.getInt("records")>1&&hello.getInt("max_record")<=32);val name=hello.getString("sni");check(name.equals("fixture.example.test",true)&&name!="fixture.example.test")
                    check(hello.getBoolean("hello_complete")&&hello.getLong("hello_ms")>=20){"ClientHello fragment delay not observed"}
                    report.accept("Optional TLS $fingerprint: real HTTP + ClientHello records/delay (${hello.getLong("hello_ms")}ms total) + native mixedcase SNI PASS")
                }
            }
        }
        listOf("raw","mux","xhttp","reality","vision","ws","grpc","httpupgrade").forEach {mode ->
            Peer(binary,"xray-$mode").use {peer ->
                val ready=peer.await("MCRF_FIXTURE_READY")
                OptionalCoreProcess.start(context,OptionalCoreConfig.Request("xray",ready.getJSONObject("outbound")),BooleanSupplier {false},false,false).use {worker ->
                    traffic(worker,ready.getString("target"),ready.getString("payload"))
                    check(ready.has("udp_target")){"Debug peer needs current UDP fixture"}
                    udpTraffic(worker,ready.getString("udp_target"))
                    report.accept("Optional Xray $mode: native HTTP + 1152-byte UDP + foreign-source refusal + control-close cleanup PASS")
                }
            }
        }
        report.accept("Optional advanced feature fixtures cleaned up; tests used no account or user endpoint")
    }
}
