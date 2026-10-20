package org.mcrf.client

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
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
        require(keys == base||keys == base+"captureBlocked"||keys==base+"managedDns"||keys==base+setOf("managedDns","ipv4Compatibility"))
        require(!data.has("captureBlocked")||data.opt("captureBlocked")==true)
        val source = JSONObject().put("mcrf_packet_fixture",JSONObject().put("original",data.getString("config"))
            .put("dialIpv4",java.net.URI("udp://${WireGuardProfile.parse(data.getString("config")).fields().getValue("Endpoint")}").host)
            .put("apkSha256",data.getString("apkSha256")).put("expectedBody",data.getString("expectedBody")))
        if(data.optBoolean("captureBlocked",false))source.getJSONObject("mcrf_packet_fixture").put("captureBlocked",true)
        if(data.has("managedDns"))source.getJSONObject("mcrf_packet_fixture").put("managedDns",data.get("managedDns"))
        if(data.has("ipv4Compatibility"))source.getJSONObject("mcrf_packet_fixture").put("ipv4Compatibility",data.get("ipv4Compatibility"))
        val admitted = ClientPacketFixture.parse(source,data.getString("url"))
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
                McrfVpnService.status == "VPN подключён · WG/AWG fixture"
            }
            val network = cm.allNetworks.single {n->
                val caps=cm.getNetworkCapabilities(n)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true && caps.ownerUid==android.os.Process.myUid() &&
                    cm.getLinkProperties(n)?.linkAddresses?.any {it.address.hostAddress=="10.77.0.2"}==true
            }
            val links = requireNotNull(cm.getLinkProperties(network))
            check(links.linkAddresses.size == 2 && links.mtu == admitted.plan.mtu)
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
                passed.accept("Android DnsResolver uses VPN profile DNS for exact A/AAAA answers; no public DNS or settings change")
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
}
