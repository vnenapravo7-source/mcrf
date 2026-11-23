package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** One small HTTPS IP trace through an owned ordinary SOCKS server. No CSQTT and no throughput. */
class RequestExitCountryInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    private class OwnedSocks:AutoCloseable {
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val connections=AtomicInteger()
        private val hosts=setOf("www.cloudflare.com","speed.cloudflare.com")
        private val allowed=hosts.flatMap {InetAddress.getAllByName(it).map {address->address.hostAddress}}.toSet()
        private val closed=AtomicBoolean()
        private val sockets=ConcurrentHashMap.newKeySet<Socket>()
        private val jobs=Executors.newCachedThreadPool {task->Thread(task,"owned-exit-proof-socks").apply {isDaemon=true}}
        init {jobs.execute {while(!closed.get())try {val client=server.accept();sockets.add(client);jobs.execute {serve(client)}}catch(_:Exception){if(!closed.get())throw IllegalStateException("Owned listener failed")}}}
        private fun exact(input:InputStream,n:Int):ByteArray {val data=ByteArray(n);var p=0;while(p<n){val count=input.read(data,p,n-p);if(count<0)throw EOFException();p+=count};return data}
        private fun serve(client:Socket) {
            var upstream:Socket?=null
            try {
                client.soTimeout=20000
                val input=client.getInputStream();val output=client.getOutputStream()
                check(input.read()==5);val n=input.read();check(n in 1..255&&exact(input,n).contains(0))
                output.write(byteArrayOf(5,0));output.flush()
                val header=exact(input,4);check(header[0].toInt()==5&&header[1].toInt()==1&&header[2].toInt()==0)
                val host=when(header[3].toInt()){
                    3->String(exact(input,input.read()),Charsets.US_ASCII).also {check(it in hosts)}
                    1->InetAddress.getByAddress(exact(input,4)).hostAddress!!.also {check(it in allowed)}
                    4->InetAddress.getByAddress(exact(input,16)).hostAddress!!.also {check(it in allowed)}
                    else->error("Unsupported SOCKS address")
                }
                val port=exact(input,2).let {((it[0].toInt()and 255)shl 8)or(it[1].toInt()and 255)}
                check(port==443)
                val endpoint=if(host in hosts)InetAddress.getAllByName(host).first {it is java.net.Inet4Address}else InetAddress.getByName(host)
                val peer=Socket();upstream=peer;sockets.add(peer);peer.soTimeout=20000;peer.connect(java.net.InetSocketAddress(endpoint,port),10000)
                connections.incrementAndGet()
                output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0));output.flush()
                val bothClosed=AtomicBoolean()
                fun stop(){if(bothClosed.compareAndSet(false,true)){client.close();peer.close();sockets.remove(client);sockets.remove(peer)}}
                jobs.execute {try {input.copyTo(peer.getOutputStream())}catch(_:Exception){}finally{stop()}}
                try {peer.getInputStream().copyTo(output)}finally{stop()}
            }catch(_:Exception){client.close();upstream?.close();sockets.remove(client);upstream?.let(sockets::remove)}
        }
        override fun close(){closed.set(true);server.close();sockets.toList().forEach {it.close()};jobs.shutdownNow();check(jobs.awaitTermination(5,TimeUnit.SECONDS))}
    }
    override fun onStart() {
        val result=Bundle();val token=DiagnosticCancellation(60000)
        try {
            check(BuildConfig.DEBUG&&android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            val before=ProfileStore.snapshot(targetContext)
            OwnedSocks().use {proxy->
                val entry=ServerImport.read("socks://127.0.0.1:${proxy.server.localPort}#RU-name-is-not-proof").single()
                val country=DiagnosticsTemporaryProxy.open(targetContext,entry,token).use {session->AutoExclusions.country(session.probe.exitTrace(token))}
                check(proxy.connections.get() in 1..2){"IP test bypassed the selected ordinary server or exceeded its bounded endpoint fallback"}
                val proof=JSONObject().put(entry.id,JSONObject().put("country",country).put("at",System.currentTimeMillis()))
                check(!AutoExclusions.allowed(entry,AutoExclusions.parse("%$country"),proof))
                val different=if(country=="RU")"DE"else"RU"
                check(AutoExclusions.allowed(entry,AutoExclusions.parse("%$different"),proof))
                check(!AutoExclusions.allowed(entry,AutoExclusions.parse("%$different"),JSONObject()))
                check(!AutoExclusions.allowed(entry,AutoExclusions.parse("name-is-not-proof"),proof))
            }
            check(ProfileStore.snapshot(targetContext)==before){"Private IP test changed saved profiles"}
            result.putString("result","PASS: one TLS-verified public exit trace crossed the selected owned ordinary SOCKS server and installed native primary module; country/text filters and unknown-country skip passed; profiles unchanged; no throughput test")
            finish(Activity.RESULT_OK,result)
        }catch(e:Throwable){android.util.Log.e("McrfExitProofTest","Exit proof regression failed",e);result.putString("failure",e.javaClass.name);finish(Activity.RESULT_CANCELED,result)}
        finally{token.cancel()}
    }
}
