package org.mcrf.client

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** Owned local peer only. Existing VPN consent is a prerequisite, never requested by this test. */
object PacketVpnDeviceChecks {
    @JvmStatic fun run(context: Context, name: String, passed: Consumer<String>) {
        try { runChecked(context,name,passed) }
        catch(error: Throwable) {throw IllegalStateException(error.message?.takeIf {it.matches(Regex("PACKET_[A-Z0-9_]+"))}?:"PACKET_FIXTURE_FAILED")}
    }
    private fun runChecked(context: Context, name: String, passed: Consumer<String>) {
        check(BuildConfig.DEBUG && VpnService.prepare(context) == null) {"PACKET_EXISTING_VPN_CONSENT_REQUIRED"}
        require(name.matches(Regex("mcrf-packet-input-[0-9a-f-]{36}\\.json")))
        val input = File(context.cacheDir.canonicalFile,name)
        require(input.isFile && input.length() in 1..65536 && input.canonicalFile == input.absoluteFile && !Files.isSymbolicLink(input.toPath()))
        val data = JSONObject(input.readText(Charsets.UTF_8))
        val keys=data.keys().asSequence().toSet()
        val base=setOf("config","url","expectedBody","apkSha256")
        val strictFields=base+"ownerPairSha256"
        val quicFields=strictFields+setOf("quicCertificate","quicCertificateSha256")
        require(keys == base||keys == base+"captureBlocked"||keys==base+"managedDns"||
            keys==base+setOf("managedDns","ipv4Compatibility")||keys==strictFields||keys==quicFields)
        require(!data.has("captureBlocked")||data.opt("captureBlocked")==true)
        val source = JSONObject().put("mcrf_packet_fixture",JSONObject().put("original",data.getString("config"))
            .put("dialIpv4",java.net.URI("udp://${WireGuardProfile.parse(data.getString("config")).fields().getValue("Endpoint")}").host)
            .put("apkSha256",data.getString("apkSha256")).put("expectedBody",data.getString("expectedBody")))
        if(data.optBoolean("captureBlocked",false))source.getJSONObject("mcrf_packet_fixture").put("captureBlocked",true)
        if(data.has("managedDns"))source.getJSONObject("mcrf_packet_fixture").put("managedDns",data.get("managedDns"))
        if(data.has("ipv4Compatibility"))source.getJSONObject("mcrf_packet_fixture").put("ipv4Compatibility",data.get("ipv4Compatibility"))
        if(data.has("ownerPairSha256"))source.getJSONObject("mcrf_packet_fixture").put("ownerPairSha256",data.get("ownerPairSha256"))
        val admitted = ClientPacketFixture.parse(source,data.getString("url"))
        val strict = admitted.ownerPairSha256
        val expectedStatus = if (strict == null) "VPN подключён · WG/AWG fixture" else "VPN подключён · WG/AWG strict owner fixture"
        val cm = context.getSystemService(ConnectivityManager::class.java)
        check(McrfVpnService.status == "Отключено" && !McrfVpnService.cleanupPending() && cm.allNetworks.none {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
        }) {"PACKET_DEVICE_MUST_BE_IDLE"}
        val before = ProfileStore.snapshot(context)
        val preferences = context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all.toMap()
        val directory = File(context.cacheDir.canonicalFile,"mcrf-test-${UUID.randomUUID()}")
        check(directory.mkdir())
        val serviceFile = File(directory,"service-fixture.json")
        var started = false
        var ownedPid = -1
        fun waitFor(timeout: Long, predicate: ()->Boolean) {
            val until = SystemClock.elapsedRealtime()+timeout
            while(!predicate()) {check(SystemClock.elapsedRealtime()<until) {"PACKET_FIXTURE_TIMEOUT"};Thread.sleep(50)}
        }
        try {
            serviceFile.writeText(JSONObject().put("source",source).put("url",data.getString("url")).put("timeout",8000).toString())
            android.system.Os.chmod(serviceFile.path,0x180)
            context.startForegroundService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.START)
                .putExtra("test_fixture",serviceFile.path).putExtra("tun",true).putExtra("applyRoutes",false).putExtra("port",10808))
            started = true
            waitFor(if(admitted.encryptedDns)150000 else 45000) {
                check(!McrfVpnService.status.startsWith("Ошибка")) {"PACKET_FIXTURE_START_FAILED"}
                McrfVpnService.status == expectedStatus
            }
            val network = cm.allNetworks.single {n->
                val caps=cm.getNetworkCapabilities(n)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true && caps.ownerUid==android.os.Process.myUid() &&
                    cm.getLinkProperties(n)?.linkAddresses?.any {it.address.hostAddress=="10.77.0.2"}==true
            }
            val links = requireNotNull(cm.getLinkProperties(network))
            check(links.linkAddresses.size == 2 && links.mtu == admitted.plan.mtu)
            if (strict != null) {
                checkExcludedOwner(context, network, strict)
                passed.accept("Strict WG active Android TUN: included UID TCP HTTP and UDP DNS IPv4/IPv6, excluded helper TCP/UDP VPN bind denied, root and unresolved owners denied by active scope")
                if (keys == quicFields) {
                    val certificate=data.opt("quicCertificate") as? String ?: error("PACKET_QUIC_CERTIFICATE")
                    val certificatePin=data.opt("quicCertificateSha256") as? String ?: error("PACKET_QUIC_CERTIFICATE")
                    ClientQuicOwnerChecks.openWireGuard(context, strict, certificate, certificatePin).use { quic ->
                        val digest=quic.runWireGuard(network, {
                            check(McrfVpnService.status == expectedStatus &&
                                cm.getNetworkCapabilities(network)?.let {
                                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&it.ownerUid==android.os.Process.myUid()
                                } == true) { "PACKET_QUIC_VPN_RETIRED" }
                        }, passed)
                        passed.accept("wgStrictQuicNonceSha256=$digest")
                    }
                }
            }
            if(admitted.captureBlocked){
                check(listOf("10.77.0.3/32","fd77::3/128").all {cidr->
                    val expected=ClientTunCidr.parse(cidr)
                    links.routes.any {route->route.destination?.let {ClientTunCidr.parse(it.toString())==expected}==true}
                }) {"PACKET_BLOCK_NOT_CAPTURED"}
                check(admitted.peerRoutes.none {route->listOf("10.77.0.3/32","fd77::3/128").any {ClientTunCidr.parse(route).overlaps(ClientTunCidr.parse(it))}})
                check(admitted.plan.routes.containsAll(listOf("10.77.0.3/32","fd77::3/128")))
                check(PacketRouteEnvelope.ownedBlockFixturePlan(admitted.plan,admitted.captured.settings).routes==admitted.peerRoutes)
                passed.accept("Owned IPv4/IPv6 BLOCK uses atomic route envelope independently recomputed and SHA-acknowledged by worker; original peer narrowed, permitted-after-block works; independent peer audit required")
            }
            passed.accept("Independent WG/AWG real Android TUN: exact local-peer HTTP payload verified over IPv4 AND IPv6")
            check(links.dnsServers==admitted.dns.map {InetAddresses.parseNumericAddress(it)}) {"PACKET_PROFILE_DNS_CHANGED"}
            if(admitted.managedDns!=null){
                passed.accept("Managed WG DNS uses actual IPv4 IPv6 kernel UDP TCP alias reflection and framework raw DNS; explicit IPv4 option respected")
                passed.accept("Managed WG DNS exact selected upstream and alias-no-peer-leak require independent peer audit")
                if(admitted.encryptedDns)passed.accept("Encrypted managed WG DNS uses kernel aliases and framework with system trust hostname checks no upstream fallback and permitted-after; independent TLS audit required")
            }else{
                passed.accept("WG/AWG exact nonce-scoped UDP DNS A/AAAA replies over IPv4 AND IPv6")
                if(strict==null)passed.accept("Android DnsResolver uses VPN profile DNS for exact A/AAAA answers; no public DNS or settings change")
            }
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            ownedPid = am.runningAppProcesses.single {it.uid==android.os.Process.myUid() && it.processName==context.packageName+":primary"}.pid
            check(ownedPid != android.os.Process.myPid())
            passed.accept("WG/AWG uses existing sole VPN owner and fresh separate native worker, not sing-box fallback")
        } finally {
            if(started) {
                context.startService(Intent(context,McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                waitFor(20000) {McrfVpnService.status == "Отключено" && !McrfVpnService.cleanupPending() &&
                    cm.allNetworks.none {cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true}}
                if(ownedPid>0) waitFor(5000) {!File("/proc/$ownedPid").exists()}
            }
            check(serviceFile.delete() || !serviceFile.exists());check(directory.delete() || !directory.exists())
            check(input.delete() || !input.exists())
            check(ProfileStore.snapshot(context)==before && context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).all==preferences) {"PACKET_USER_STATE_CHANGED"}
        }
        passed.accept("WG/AWG STOP retires owned VPN/worker; private inputs removed and user profiles/preferences preserved")
    }

    private class OwnerProbe(val nonce: String) {
        val result = CompletableFuture<Int>()
        val retired = CompletableFuture<Unit>()
    }

    private fun checkExcludedOwner(context: Context, network: Network, pairPin: String) {
        val packages = ClientP1OwnerFixtureScope.packages(context, pairPin)
        val helper = packages[1]
        val uid = context.packageManager.getApplicationInfo(helper, 0).uid
        val current = AtomicReference<OwnerProbe?>()
        val remote = CompletableFuture<Messenger>()
        val thread = HandlerThread("wg-excluded-owner-reply").apply { start() }
        val reply = Messenger(Handler(thread.looper) { message ->
            val probe = current.get()
            if (probe != null && message.sendingUid == uid && message.data.getString("nonce") == probe.nonce) {
                when (message.what) {
                    3, 5, 7 -> probe.result.complete(message.what)
                    4 -> probe.retired.complete(Unit)
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote.complete(Messenger(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                current.get()?.result?.completeExceptionally(IllegalStateException("PACKET_STRICT_HELPER_DIED"))
            }
        }
        var bound = false
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(helper, "$helper.OwnerProbeService")),
                connection, Context.BIND_AUTO_CREATE)
            check(bound) { "PACKET_STRICT_HELPER_BIND" }
            val service = remote.get(5, TimeUnit.SECONDS)
            for (host in listOf("10.77.0.1", "fd77::1")) for (protocol in listOf(6, 17)) {
                val probe = OwnerProbe(UUID.randomUUID().toString().replace("-", ""))
                current.set(probe)
                try {
                    service.send(Message.obtain(null, 8).apply {
                        replyTo = reply
                        data = Bundle().apply {
                            putString("nonce", probe.nonce)
                            putString("host", host)
                            putInt("port", if (protocol == 6) 8080 else 53)
                            putInt("protocol", protocol)
                            putLong("network", network.networkHandle)
                            putBoolean("connected", true)
                        }
                    })
                    check(probe.result.get(5, TimeUnit.SECONDS) == 7) { "PACKET_STRICT_EXCLUDED_VPN_BIND" }
                    probe.retired.get(5, TimeUnit.SECONDS)
                } finally {
                    if (!probe.retired.isDone) service.send(Message.obtain(null, 2).apply {
                        replyTo = reply
                        data = Bundle().apply { putString("nonce", probe.nonce) }
                    })
                    current.set(null)
                }
            }
            check(ClientP1OwnerFixtureScope.packages(context, pairPin) == packages) { "PACKET_STRICT_HELPER_CHANGED" }
        } finally {
            try { if (bound) context.unbindService(connection) } finally {
                thread.quitSafely()
                thread.join(5000)
                check(!thread.isAlive) { "PACKET_STRICT_HELPER_REPLY_THREAD" }
            }
        }
    }
}
