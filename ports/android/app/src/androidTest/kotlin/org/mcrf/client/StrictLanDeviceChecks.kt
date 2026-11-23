package org.mcrf.client

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Strict TUN plus the production LAN mixed listener. Only a private owned
 * synthetic TCP target is routed, with a port override to our loopback origin. */
internal class StrictLanDeviceChecks(
    private val test:Instrumentation,
    private val context:Context,
    private val address:String,
    private val pairPin:String,
    private val nonce:String
):AutoCloseable {
    init {check(BuildConfig.DEBUG&&address in LanSharingAddresses.discover()){"STRICT_LAN_PHYSICAL_IPV4_REQUIRED"}}
    private val origin=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
    private val port=try {ServerSocket(0).use {it.localPort}} catch(error:Exception) {origin.close();throw error}
    private val password=ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") {"%02x".format(it)}
    fun json()=JSONObject().put("port",port).put("originPort",origin.localPort).put("password",password)

    fun run(live:()->Unit) {
        check(address in LanSharingAddresses.discover()){"STRICT_LAN_PHYSICAL_ADDRESS_CHANGED"}
        live()
        checkExcluded(live)
        assertQuiet()
        val ready=JSONObject().put("address",address).put("port",port)
            .put("target",ClientP1StrictOwnerFixture.LAN_TARGET).put("targetPort",80).put("nonce",nonce)
        test.sendStatus(0,Bundle().apply {putString("stream","STRICT_LAN_READY $ready\n")})
        val end=SystemClock.elapsedRealtime()+75_000
        val pending=mutableSetOf("/$nonce-http","/$nonce-socks")
        while(pending.isNotEmpty()) {
            live()
            val left=end-SystemClock.elapsedRealtime()
            check(left>0){"STRICT_LAN_REMOTE_CLIENT_TIMEOUT"}
            origin.soTimeout=minOf(left,1000).toInt()
            val accepted=try {origin.accept()} catch(_:SocketTimeoutException) {continue}
            accepted.use {socket->
                check(socket.inetAddress.isLoopbackAddress)
                socket.soTimeout=2500
                // Read a bounded request. Any unexpected accept or duplicate
                // is a failure, even if it never receives a nonce reply.
                val input=socket.getInputStream()
                val bytes=java.io.ByteArrayOutputStream()
                var tail=0
                while(tail!=0x0d0a0d0a) {
                    check(bytes.size()<8192){"STRICT_LAN_ORIGIN_HEADER_BOUND"}
                    val next=input.read()
                    check(next in 9..126){"STRICT_LAN_ORIGIN_HEADER"}
                    bytes.write(next)
                    tail=(tail shl 8) or next
                }
                val request=bytes.toString("US-ASCII").substringBefore("\r\n").split(' ')
                check(request.size==3&&request.first()=="GET"&&pending.remove(request[1])){"STRICT_LAN_UNEXPECTED_OR_DUPLICATE_EGRESS"}
                val body=nonce.toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
            }
        }
        assertQuiet()
        live()
    }

    private fun assertQuiet() {
        origin.soTimeout=750
        try {origin.accept().use {error("STRICT_LAN_DENIED_REQUEST_REACHED_ORIGIN")}}
        catch(_:SocketTimeoutException) { }
    }

    private fun checkExcluded(live:()->Unit) {
        val packages=ClientP1OwnerFixtureScope.packages(context,pairPin)
        val helper=packages[1]
        val manager=context.packageManager
        val uid=manager.getApplicationInfo(helper,0).uid
        check(uid!=android.os.Process.myUid()&&manager.getPackagesForUid(uid)?.toSet()==packages.drop(1).toSet())
        val component=ComponentName(helper,"$helper.ProxyProbeService")
        val info=manager.getServiceInfo(component,0)
        check(info.enabled&&info.exported&&info.permission=="org.mcrf.client.permission.P1_OWNER")
        val thread=HandlerThread("strict-lan-owner-control").apply {start()}
        val connected=CompletableFuture<Messenger>()
        val binding=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){connected.complete(Messenger(binder))}
            override fun onServiceDisconnected(name:ComponentName)=Unit
        }
        var bound=false
        try {
            bound=context.bindService(Intent().setComponent(component),binding,Context.BIND_AUTO_CREATE)
            check(bound)
            val remote=connected.get(5,TimeUnit.SECONDS)
            for(listener in listOf("127.0.0.1",address)) {
                live()
                val request=UUID.randomUUID().toString().replace("-","")
                val result=CompletableFuture<Int>()
                val retired=CompletableFuture<Unit>()
                val reply=Messenger(Handler(thread.looper){message->
                    val data=message.data
                    if(message.sendingUid==uid&&data.getString("session")==request&&data.getString("nonce")==nonce) {
                        when(message.what) {
                            3->result.complete(if(data.getInt("uid")==uid&&data.getString("kind")=="LAN_HTTP_DENIED")3 else 0)
                            5->result.complete(5)
                            4->retired.complete(Unit)
                        }
                    }
                    true
                })
                fun send(code:Int,payload:Bundle) {
                    remote.send(Message.obtain(null,code).apply {data=payload;replyTo=reply})
                }
                val ids=Bundle().apply {putString("nonce",nonce);putString("session",request)}
                send(1,Bundle(ids).apply {
                    putInt("port",port)
                    putString("kind","LAN_HTTP_DENIED")
                    putInt("family",4)
                    putString("listener",listener)
                    putString("password",password)
                })
                try {
                    check(result.get(12,TimeUnit.SECONDS)==3){"STRICT_LAN_EXCLUDED_UID_NEGATIVE_FAILED"}
                    assertQuiet()
                } finally {
                    send(2,ids)
                    retired.get(5,TimeUnit.SECONDS)
                }
            }
            check(ClientP1OwnerFixtureScope.packages(context,pairPin)==packages)
        } finally {
            if(bound)context.unbindService(binding)
            thread.quitSafely()
            thread.join(3000)
            check(!thread.isAlive)
        }
    }

    override fun close()=origin.close()
}
