package org.mcrf.client

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TelegramDeviceChecks {
    @JvmStatic fun run(test:Instrumentation,report:Consumer<String>) {
        val context=test.targetContext
        val before=ProfileStore.snapshot(context)
        val activity=test.startActivitySync(Intent(context,MainActivity::class.java).putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val received=AtomicBoolean()
        lateinit var bridge:TelegramBridge
        test.runOnMainSync {bridge=TelegramBridge(activity){received.set(true)};bridge.bind()}
        fun await(condition:()->Boolean){val until=SystemClock.elapsedRealtime()+15000;while(!condition()&&SystemClock.elapsedRealtime()<until){test.waitForIdleSync();SystemClock.sleep(150)};check(condition()){ "Telegram service did not reach expected state: ${bridge.state}" }}
        fun passed(value:String){report.accept(value)}
        try {
            await{received.get()}
            check(!bridge.state.wanted) {"Refusing to interrupt an already running user Telegram bridge"}
            passed("Telegram service initially off; no user connection interrupted")
            test.runOnMainSync {bridge.setEnabled(true)}
            await{bridge.state.running}
            socket().use {it.outputStream.write(byteArrayOf(5,1,0));check(it.inputStream.read()==5&&it.inputStream.read()==0)}
            passed("Real native Android SOCKS5 no-auth greeting on loopback")
            check(TelegramBridge.setupLink(bridge.state.port)=="https://t.me/socks?server=127.0.0.1&port=19071")
            passed("Telegram setup link carries the actual local SOCKS port")
            // Public unauthenticated MTProto req_pq_multi, no account, token or login.
            var proof=false
            for(ip in listOf("149.154.167.220","149.154.167.91")) {
                try{handshake(ip,if(ip.endsWith(".91"))4 else 2);test.waitForIdleSync();SystemClock.sleep(250);proof=true;passed("Telegram MTProto res_pq and matching nonce received through the bridge ($ip), transport: ${bridge.state.transport}");if(bridge.state.transport=="WebSocket")break}
                catch(_:Exception){ }
            }
            if(!proof)passed("Telegram real transport not confirmed on the current network (no res_pq); SOCKS readiness alone is not connectivity proof")
            if(android.net.VpnService.prepare(context)==null&&!McrfVpnService.status.startsWith("VPN подключён")&&!McrfVpnService.status.startsWith("Прокси ")) {
                val traffic=TrafficState.current
                val directory=java.io.File(context.cacheDir,"mcrf-test-${java.util.UUID.randomUUID()}").apply {check(mkdir())}
                val fixture=java.io.File(directory,"service-fixture.json")
                val direct=org.json.JSONObject().put("outbounds",org.json.JSONArray().put(org.json.JSONObject().put("type","direct").put("tag","direct"))).put("route",org.json.JSONObject().put("final","direct"))
                fixture.writeText(org.json.JSONObject().put("source",direct).put("url","http://127.0.0.1/").toString());android.system.Os.chmod(fixture.absolutePath,0x180)
                val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {it.localPort}
                try {
                    context.startForegroundService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.START).putExtra("tun",true).putExtra("port",port).putExtra("test_fixture",fixture.absolutePath))
                    await {McrfVpnService.status.startsWith("VPN подключён")}
                    passed("Owned direct TUN fixture starts with already granted Android VPN permission")
                    handshake("149.154.167.220",2)
                    passed("Telegram returns matching res_pq while ordinary MCRF TUN is active")
                }finally {
                    context.startService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                    await {McrfVpnService.status=="Отключено"}
                    fixture.delete();directory.delete();TrafficState.current=traffic
                }
            }else passed("TUN coexistence not tested: requires existing MCRF VPN permission and no active user connection")
            // Repeated on is idempotent and off can cancel a start.
            test.runOnMainSync {bridge.setEnabled(true);bridge.setEnabled(false)}
            await{!bridge.state.wanted&&!bridge.state.running}
            val deadline=SystemClock.elapsedRealtime()+10000
            while(SystemClock.elapsedRealtime()<deadline) {if(free())break;SystemClock.sleep(150)}
            check(free()){ "Telegram process kept the local port after STOP" }
            passed("Immediate on/off stops the owned native process and releases the port")
            check(ProfileStore.snapshot(context)==before)
            passed("Telegram test preserved encrypted profiles, routes and subscription data")
        }finally {
            if(bridge.state.wanted)test.runOnMainSync {bridge.setEnabled(false)}
            test.runOnMainSync {bridge.unbind();activity.finish()}
        }
    }
    private fun free():Boolean=try{ServerSocket(TelegramBridge.DEFAULT_PORT,1,InetAddress.getByName("127.0.0.1")).use {true}}catch(_:Exception){false}
    private fun socket()=Socket().apply {connect(InetSocketAddress("127.0.0.1",TelegramBridge.DEFAULT_PORT),1000);soTimeout=35000}
    private fun cipher(key:ByteArray,iv:ByteArray)=Cipher.getInstance("AES/CTR/NoPadding").apply {init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv))}
    private fun handshake(ip:String,dc:Int) {
        socket().use {socket ->
            val input=socket.inputStream;val output=socket.outputStream
            output.write(byteArrayOf(5,1,0));check(input.read()==5&&input.read()==0)
            output.write(byteArrayOf(5,1,0,1)+InetAddress.getByName(ip).address+byteArrayOf(1,187.toByte()))
            val reply=input.readNBytes(10);check(reply.size==10&&reply[1].toInt()==0)
            val random=SecureRandom();val header=ByteArray(64).also(random::nextBytes)
            header[0]=0x71 // Not an HTTP/TLS/abridged prefix.
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(56,0xeeeeeeee.toInt()).putShort(60,dc.toShort())
            val reversed=header.reversedArray()
            val send=cipher(header.copyOfRange(8,40),header.copyOfRange(40,56))
            val receive=cipher(reversed.copyOfRange(8,40),reversed.copyOfRange(40,56))
            val encrypted=send.update(header);encrypted.copyInto(header,56,56,64);output.write(header)
            val nonce=ByteArray(16).also(random::nextBytes)
            val request=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).putInt(40).putLong(0).putLong((System.currentTimeMillis()/1000L shl 32) or 4L).putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
            output.write(send.update(request));output.flush()
            val length=ByteBuffer.wrap(receive.update(input.readNBytes(4).also {check(it.size==4)})).order(ByteOrder.LITTLE_ENDIAN).int
            check(length in 40..65536)
            val response=receive.update(input.readNBytes(length).also {check(it.size==length)})
            check(ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).getInt(20)==0x05162463)
            check(response.copyOfRange(24,40).contentEquals(nonce))
        }
    }
}
