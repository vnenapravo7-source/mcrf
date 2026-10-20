package org.mcrf.client

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Engine
import org.mcrf.engine.spi.v1.Platform
import org.mcrf.steerprep.SteerMcrfSnapshotAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/** Main-only exclusive DEBUG instrumentation source; not a test result.
 * Requires the reviewed private service wire and real DEBUG HostRun ACK observations.
 * PC peer is started/owned by the coordinator, never by this class. No public endpoint.
 * Main must cross-match this phone receipt with the PC peer receipt before acceptance.
 */
object ClientSteerVpnDeviceChecks {
    private const val TARGET = "198.18.0.2"
    private const val TUN = "10.89.0.2"
    private const val TAG = "owned-steer-fixture"
    private val leaf = Regex("mcrf-test-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    /** temporary already contains ONLY steer-fixture-input.json placed privately by main.
     * Input schema: {schema:1, peer:<fresh PC fixture-private.json>, snapshot:<full synthetic template>}.
     * Service wire: {source:{mcrf_steer_fixture:{original,snapshot,dialIpv4}},url,timeout:8000}.
     */
    @JvmStatic fun run(context: Context, temporary: File, passed: Consumer<String>) = runMode(context,temporary,passed,false)
    @JvmStatic fun runP1(context: Context, temporary: File, passed: Consumer<String>) = runMode(context,temporary,passed,true)
    @JvmStatic fun runP1Proxy(context:Context,temporary:File,passed:Consumer<String>) = runMode(context,temporary,passed,true,true)
    private fun runMode(context: Context, temporary: File, passed: Consumer<String>, p1:Boolean, proxy:Boolean=false) {
        try { runChecked(context, temporary, passed,p1,proxy) }
        catch (error: Throwable) {
            val code = error.message?.takeIf { it.matches(Regex("STEER_[A-Z0-9_]+")) } ?: "STEER_PRIVATE_FIXTURE_FAILED"
            throw IllegalStateException(code) // No cause: malformed private input/URI exceptions may contain credentials.
        }
    }
    private fun runChecked(context: Context, temporary: File, passed: Consumer<String>,p1:Boolean,proxy:Boolean) {
        val app = context.applicationContext
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper() && Application.getProcessName() == app.packageName) { "STEER_DEBUG_MAIN_BACKGROUND_REQUIRED" }
        check(VpnService.prepare(app) == null) { "STEER_EXISTING_VPN_CONSENT_REQUIRED" }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        requireIdle(app, cm)
        val root = File(app.cacheDir.canonicalFile, temporary.name)
        require(leaf.matches(root.name) && temporary.parentFile?.canonicalFile == app.cacheDir.canonicalFile &&
            temporary.canonicalFile == root && root.isDirectory && !Files.isSymbolicLink(temporary.toPath()) &&
            !Files.isSymbolicLink(root.toPath()) && root.list()?.toSet() == setOf("steer-fixture-input.json")) { "STEER_PRIVATE_INPUT_DIRECTORY" }
        val input = privateJson(File(root, "steer-fixture-input.json"), root)
        require(input.keys().asSequence().toSet() == (if(p1)setOf("schema", "peer", "snapshot","moduleSha256")+(if(proxy)setOf("helperSha256")else emptySet()) else setOf("schema", "peer", "snapshot"))) { "STEER_INPUT_FIELDS" }
        require(input.opt("schema") is Int && input.getInt("schema") == (if(p1)2 else 1)) { "STEER_INPUT_SCHEMA" }
        val peer = input.getJSONObject("peer")
        require(peer.keys().asSequence().toSet() == setOf("schema", "uri", "listenIpv4", "listenPort", "targetIpv4", "targetPort", "nonce", "path", "payloadHex", "deadlineSeconds", "maxConnections") &&
            peer.opt("schema") is Int && peer.getInt("schema") == (if(p1)2 else 1)) { "STEER_PEER_SCHEMA" }
        require(
            listOf("listenPort", "targetPort", "deadlineSeconds", "maxConnections").all { peer.opt(it) is Int } &&
            listOf("uri", "listenIpv4", "targetIpv4", "nonce", "path", "payloadHex").all { peer.opt(it) is String }) { "STEER_PEER_INPUT_FIELDS" }
        val original = peer.getString("uri")
        val uri = try { URI(original) } catch (_: Exception) { error("STEER_URI_FORMAT") }
        val host = peer.getString("listenIpv4")
        val address = numeric(host)
        val octets = address.address.map { it.toInt() and 255 }
        require(octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 192 && octets[1] == 168) { "STEER_PRIVATE_PC_IPV4_REQUIRED" }
        val id = try { UUID.fromString(uri.userInfo) } catch (_: Exception) { error("STEER_FRESH_UUID_REQUIRED") }
        require(id.version() == 4 && id.variant() == 2 && uri.userInfo == id.toString() && uri.scheme == "vless" && uri.host == host &&
            uri.port == peer.getInt("listenPort") && uri.port in 1..65535 && uri.rawPath.orEmpty().isEmpty() && uri.fragment == null &&
            uri.rawQuery == "security=none&type=tcp&encryption=none") { "STEER_ORIGINAL_PRIVATE_URI_REQUIRED" }
        val nonce = peer.getString("nonce")
        require(nonce.matches(Regex("[0-9a-f]{32}")) && peer.getString("path") == "/owned/$nonce" &&
            peer.getString("targetIpv4") == TARGET && peer.getInt("targetPort") == 80 &&
            peer.getInt("deadlineSeconds") == (if(p1)120 else 45)) { "STEER_OWNED_PEER_DEADLINE" }
        require(peer.getInt("maxConnections") == (if(p1)64 else 32)) { "STEER_OWNED_PEER_CONTRACT" }
        val hex = peer.getString("payloadHex")
        require(hex.matches(Regex("[0-9a-f]{192}"))) { "STEER_OWNED_96_BYTE_PAYLOAD_REQUIRED" }
        val payload = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val full = input.getJSONObject("snapshot")
        val tlsProtocol=if(p1&&!proxy)full.getJSONObject("routing").getJSONObject("globalDns").getString("protocol").takeIf {it in setOf("DoT","DoH")}else null
        val projected = SteerMcrfSnapshotAdapter.convert(full.toString())
        require(projected.supported && projected.settingsJson != null) { "STEER_CANONICAL_FULL_SNAPSHOT_REJECTED" }
        val settings = requireNotNull(projected.settingsJson)
        val reduced = JSONObject(settings)
        val helperSha=if(proxy)input.getString("helperSha256").also {ClientP1OwnerFixtureScope.packages(app,it)} else null
        if(proxy)ClientSteerFixture.requireP1ProxySnapshot(full,nonce) else if(tlsProtocol!=null)ClientSteerFixture.requireP1TlsSnapshot(full,tlsProtocol) else if(p1)ClientSteerFixture.requireP1Snapshot(full) else {
        val rule = reduced.getJSONArray("rules").also { require(it.length() == 1) }.getJSONObject(0)
        require(reduced.getString("defaultTarget") == "block" && rule.getString("text") == "IP-CIDR,198.18.0.2/32" &&
            rule.getString("mode") == "addresses" && rule.getString("target") == "proxy" && !rule.getBoolean("invert") &&
            reduced.getJSONObject("globalDns").let { it.getString("provider") == "Свой" && it.getString("protocol") == "UDP" && it.getString("custom") == "198.18.0.254" }) { "STEER_BOUNDED_POLICY_REQUIRED" }
        }
        // Exact snapshot admission is the SAME pure parser used by the service;
        // no test-side relaxed JSON schema or independently invented settings.
        ClientSteerFixture.parse(JSONObject().put("mcrf_steer_fixture",JSONObject()
            .put("original",original).put("snapshot",full).put("dialIpv4",host)),"http://$TARGET${peer.getString("path")}")
        EngineUpdatePrimary.installation(app, "steer")
        @Suppress("DEPRECATION") val installed = app.packageManager.getPackageInfo("org.mcrf.engines.steer", 0)
        if(!p1)require(installed.longVersionCode == 5L) { "STEER_REVIEWED_INSTALLED_VERSION5_REQUIRED" }
        val pinnedModule=if(p1)input.getString("moduleSha256").also {
            require(it.matches(Regex("[0-9a-f]{64}"))&&ClientPrimaryWorkerWire.hashFile(File(requireNotNull(installed.applicationInfo).sourceDir))==it) {"STEER_P1_MODULE_PIN"}
            // The following REAL isolated validate/tunPlan independently demand
            // model2/native6/ruleDNS declarations. Never synthesize capabilities.
        } else null
        val saved = Saved.capture(app)
        val baselineRuntime = runtimeNames(app)
        val baselineTuns = tunInterfaces(Process.myPid())
        val mainPid = Process.myPid()
        val fixture = File(root, "service-fixture.json")
        val receiptFile = File(root, "steer-vpn-device-receipt.json")
        val health = "http://$TARGET${peer.getString("path")}"
        val receipt = JSONObject().put("schema", 1).put("actual_gate_passed", false).put("android_segment_passed", false).put("host_peer_crossmatch_required", true)
            .put("inputSha256", hash(File(root, "steer-fixture-input.json").readBytes())).put("revoke_verified", false)
        if(p1)receipt.put("schema",2).put("moduleSha256",pinnedModule).put("full_p1_accepted",false)
        var run: Run? = null
        var attemptedStart = false
        var madeFixture = false
        var stage = "PREPARED"
        var failed = false
        fun verify(ok: Boolean, label: String) { check(ok) { "STEER_GATE_$stage" }; passed.accept(label) }
        try {
            verify(true, if(p1)"P1 Steer fixture pins installed same-signer module and exact model2 synthetic snapshot without touching saved settings" else "Steer fixture verifies installed same-signer version5 and canonical full bounded snapshot without touching saved settings")
            stage = "PREFLIGHT_CONNECT"
            val preflight = ClientPrimaryWorker.connect(app, "steer", System.nanoTime().coerceAtLeast(1), null)
            val preflightPid = preflight.remotePid
            var preflightContentPassed = false
            try {
                stage = "PREFLIGHT_VALIDATE_CALL"
                val validation = preflight.validate(original, settings, Engine.Mode.VPN)
                if (!validation.supported) {
                    stage = "PREFLIGHT_VALIDATE_REJECTED"
                    error("STEER_PREFLIGHT_REJECTED")
                }
                stage = "PREFLIGHT_VALIDATION_DTO"
                require(validation.tunAcquisition == Engine.TunAcquisition.PROVIDED)
                stage = "PREFLIGHT_PLAN_CALL"
                val planJson = preflight.tunPlan(original, settings, Engine.Mode.VPN)
                stage = "PREFLIGHT_PLAN_CONTENT"
                val plan = JSONObject(planJson)
                fun exactSingleString(key: String, expected: String): Boolean {
                    val values = plan.getJSONArray(key)
                    return values.length() == 1 && values.get(0) is String && values.getString(0) == expected
                }
                require(plan.keys().asSequence().toSet() == setOf("schema", "mtu", "addresses", "routes", "dns", "excludedApps", "ipv6", "hostMustBeInsideVpn", "requiresVpnNetworkHandle") &&
                    (if(p1) plan.getInt("schema")==2 && plan.getJSONArray("addresses").let {it.length()==2&&it.getString(0)=="10.89.0.2/32"&&it.getString(1)=="fdfe:dcba:9876::1/126"} &&
                        plan.getJSONArray("routes").let {it.length()==2&&it.getString(0)=="0.0.0.0/0"&&it.getString(1)=="::/0"} &&plan.getString("ipv6")=="capture_v6_native"
                    else
                    plan.getInt("schema") == 1 && plan.getInt("mtu") == 1500 && exactSingleString("addresses", "10.89.0.2/32") &&
                    exactSingleString("routes", "0.0.0.0/0") && plan.getString("ipv6") == "block_for_captured_apps") &&
                    plan.getInt("mtu")==1500&&exactSingleString("dns", "10.89.0.1") &&plan.getJSONArray("excludedApps").length() == 0 &&
                    plan.getBoolean("hostMustBeInsideVpn") && plan.getBoolean("requiresVpnNetworkHandle"))
                preflightContentPassed = true
            } finally {
                // Preserve the failed validation/plan stage even if worker close also fails.
                if (preflightContentPassed) stage = "PREFLIGHT_CLOSE"
                preflight.close()
            }
            stage = "PREFLIGHT_DEATH"
            verify(processGone(preflightPid) && workers().isEmpty(), "Actual installed Steer validates the original private URI and exact provided-TUN plan, then preflight worker death is observed")
            require(fixture.createNewFile()) { "STEER_FIXTURE_ALREADY_EXISTS" }
            madeFixture = true
            Os.chmod(fixture.path, 0x180)
            val serviceInput=JSONObject().put("source", JSONObject().put("mcrf_steer_fixture", JSONObject()
                .put("original", original).put("snapshot", full).put("dialIpv4", host)))
                .put("url", health).put("timeout", 8000)
            if(proxy)serviceInput.put("p1OwnerHelperSha256",helperSha)
            fixture.writeText(serviceInput.toString(), Charsets.UTF_8)
            stage = "START"
            attemptedStart = true
            app.startForegroundService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.START)
                .putExtra("tun", true).putExtra("applyRoutes", false).putExtra("port", 10808).putExtra("test_fixture", fixture.path))
            check(await(20000) {
                if (McrfVpnService.status.startsWith("Ошибка")) error("STEER_SERVICE_FIXTURE_START_REJECTED")
                val service = currentService()
                val worker = workers().singleOrNull()
                if (service == null || worker == null || worker.choiceId != "steer" || worker.state != "STARTING" ||
                    !McrfVpnService.status.startsWith("VPN подключён")) false
                else { run = Run.capture(service, worker, cm, app); true }
            }) { "STEER_ACTUAL_SERVICE_OR_CALLBACK_WIRE_NOT_READY" }
            val actual = requireNotNull(run)
            actual.assertCurrent(cm)
            require(actual.tun.fileDescriptor.valid() && !McrfVpnService.nativeVpnExclusion &&
                if(proxy)McrfVpnService.localProxyPort in 1024..65535 else McrfVpnService.localProxyPort==0)
            val iface = requireNotNull(cm.getLinkProperties(actual.network)?.interfaceName)
            require(tunInterfaces(mainPid).contains(iface) && tunInterfaces(actual.pid).contains(iface)) { "STEER_REAL_MAIN_AND_WORKER_TUN_FDS_REQUIRED" }
            val links = requireNotNull(cm.getLinkProperties(actual.network))
            require(links.mtu == 1500 && links.linkAddresses.size == (if(p1)2 else 1)) {"STEER_TUN_ADDRESS_COUNT"}
            require(links.linkAddresses.any { it.address.hostAddress == TUN && it.prefixLength == 32 } &&
                links.dnsServers.map { it.hostAddress } == listOf("10.89.0.1") && links.routes.any { it.destination.contains(numeric(TARGET)) })
            assertMutationBlocked(app)
            verify(true, "Actual main Steer service owns TUN and authenticated child has the same interface, with no owner UID exclusion")
            stage = "DATA"
            val before = actual.worker.stats()
            val get = http(actual, cm, peer.getString("path"), null)
            val post = http(actual, cm, peer.getString("path"), payload)
            verify(get.status == 204 && get.body.isEmpty() && post.status == 200 && post.body.contentEquals(payload),
                "VPN-source10.89.0.2 GET204 and POST200 return only the owned nonce path and exact private96B payload")
            if(proxy){
                stage="P1_ORIGINAL_UID_AUTH_PROXY"
                receipt.put("p1_proxy",ClientSteerP1ProxyChecks.run(app,actual.network,requireNotNull(helperSha),nonce){actual.assertCurrent(cm)})
                verify(true,"P1 actual different-UID authenticated HTTP CONNECT absolute HTTP SOCKS TCP UDP DNS and denied/allowed-after controls")
                val v6=http(actual,cm,peer.getString("path"),null,"fd77::2","fdfe:dcba:9876::1")
                require(v6.status==204);receipt.put("ipv6ResponseSha256",v6.peerWireSha)
            }else if(tlsProtocol!=null){
                receipt.put("p1_tls",ClientSteerP1DnsChecks.runTls(actual.network,nonce,{actual.assertCurrent(cm)},{stage=it}))
                val v6=http(actual,cm,peer.getString("path"),null,"fd77::2","fdfe:dcba:9876::1")
                verify(v6.status==204,"P1 actual encrypted DNS global success wrong-hostname untrusted-cert rejection without fallback and permitted-after completed")
                receipt.put("ipv6ResponseSha256",v6.peerWireSha)
            }else if(p1){
                stage="P1_NATIVE_DNS_AND_IPV6"
                receipt.put("p1_dns",ClientSteerP1DnsChecks.run(actual.network,nonce,{actual.assertCurrent(cm)},{stage=it}) {alias,qtype->
                    val reply=http(actual,cm,peer.getString("path"),null,requireNotNull(InetAddress.getByAddress(alias).hostAddress),
                        if(qtype==1)TUN else "fdfe:dcba:9876::1",if(qtype==1)TARGET else "fd77::2")
                    require(reply.status==204);reply.peerWireSha
                })
                verify(true,"P1 actual global and per-rule DNS A AAAA UDP TCP plus native alias traffic completed")
                val v6=http(actual,cm,peer.getString("path"),null,"fd77::2","fdfe:dcba:9876::1")
                verify(v6.status==204,"P1 actual IPv6 native TCP carries owned HTTP response through the same selected VLESS peer")
                receipt.put("ipv6ResponseSha256",v6.peerWireSha)
            }
            verify(await(6000) {
                actual.assertCurrent(cm)
                val after = actual.worker.stats()
                after.txPayload.value != null && after.rxPayload.value != null && before.txPayload.value != null && before.rxPayload.value != null &&
                    after.txPayload.value!! > before.txPayload.value!! && after.rxPayload.value!! >= before.rxPayload.value!! + payload.size
            }, "Actual installed Steer native TX/RX counters increase after real VPN GET/POST data")
            stage = "CALLBACK_EVIDENCE"
            val ackCount = actual.acks()
            require(field(actual.platform.javaClass, actual.platform, "generation") == actual.worker.generation && ackCount > 0 &&
                actual.lastBoundNetwork() == actual.physical.networkHandle) { "STEER_REAL_BINDER_PROTECT_BIND_OBSERVATION_REQUIRED" }
            // No test call to protectAndBind has occurred: these combined ACKs follow real
            // native forwarding and count only success of protect+bind+final generation checks.
            // Counter alone does not prove Binder reply delivery; real data and peer join are essential.
            verify(true, "Actual active HostRun records successful protect-and-physical-bind ACKs after native forwarded VPN HTTP")
            receipt.put("mainPid", mainPid).put("workerPid", actual.pid).put("generation", actual.worker.generation)
                .put("vpnNetworkHandle", actual.network.networkHandle).put("ownerUid", Process.myUid()).put("tunInterface", iface)
                .put("physicalNetworkHandle", actual.physical.networkHandle).put("getResponseSha256", get.peerWireSha)
                .put("postResponseSha256", post.peerWireSha).put("postBodyBytes", payload.size)
                .put("protectAndBindAcks", ackCount).put("lastBoundNetworkHandle", actual.lastBoundNetwork())
            stage = "STOP"
            stopOwned(app, actual.service, health)
            verify(await(20000) { clean(app, cm, actual, baselineRuntime, baselineTuns) },
                "Actual Steer STOP observes Binder/PID death, closes TUN/leases/runtime and preserves the main process")
            require(Process.myPid() == mainPid)
            val stoppedAcks = actual.acks()
            Socket().use { socket ->
                socket.bind(InetSocketAddress(0))
                ParcelFileDescriptor.fromSocket(socket).use { duplicate ->
                    require(!actual.platform.protectAndBind(actual.worker.generation, duplicate.fd) && actual.acks() == stoppedAcks)
                }
            }
            verify(true, "After real STOP the retired callback refuses its own stale generation and cannot increment successful ACKs")
            assertMutationFree(app)
            if(p1)require(ClientPrimaryWorkerWire.hashFile(File(requireNotNull(installed.applicationInfo).sourceDir))==pinnedModule) {"STEER_P1_MODULE_CHANGED"}
            verify(saved == Saved.capture(app), "Steer fixture leaves every saved store, generation, full preference and protected file unchanged without restoration")
            receipt.put("android_segment_passed", true).put("stage", "ANDROID_DATA_AND_STOP_COMPLETE")
                .put("host_peer_crossmatch_required", true).put("user_state_unchanged", true)
        } catch (error: Throwable) {
            if(p1){
                // Safe fixed machine codes/type ONLY; no exception text, URI,
                // query, nonce, payload or preferences enter a receipt.
                val code=error.message?.takeIf {it.matches(Regex("(?:P1|STEER)_[A-Z0-9_]{1,48}"))}
                    ?:error.javaClass.simpleName.uppercase(java.util.Locale.ROOT).replace(Regex("[^A-Z0-9_]"),"").take(24)
                stage=(stage+"_"+code).take(64)
            }
            failed = true
            receipt.put("stage", stage).put("actual_gate_passed", false)
        } finally {
            var cleanupFailure = false
            try {
                if (attemptedStart) currentService()?.let { stopOwned(app, it, health) }
                if (attemptedStart) check(await(20000) {
                    val actual = run
                    if (actual != null) clean(app, cm, actual, baselineRuntime, baselineTuns)
                    else currentService() == null && workers().isEmpty() && runtimeNames(app) == baselineRuntime && tunInterfaces(mainPid) == baselineTuns
                })
                check(workers().isEmpty() && runtimeNames(app) == baselineRuntime && tunInterfaces(mainPid) == baselineTuns)
                check(saved == Saved.capture(app))
            } catch (_: Throwable) { cleanupFailure = true }
            try {
                if (madeFixture && fixture.exists()) check(!Files.isSymbolicLink(fixture.toPath()) && fixture.canonicalFile.parentFile == root && fixture.delete())
                receipt.put("cleanup_verified", !cleanupFailure).put("user_state_unchanged", saved == Saved.capture(app))
                    .put("android_segment_passed", receipt.optBoolean("android_segment_passed") && !cleanupFailure)
                require(!receiptFile.exists())
                val pending = File(root, "steer-vpn-device-receipt.tmp")
                require(pending.createNewFile()); Os.chmod(pending.path, 0x180)
                pending.writeText(receipt.toString(), Charsets.UTF_8); require(pending.renameTo(receiptFile))
            } catch (_: Throwable) { cleanupFailure = true }
            if (cleanupFailure) { if (!failed) stage = "CLEANUP_OR_UNCHANGED_STATE"; failed = true }
        }
        check(!failed) { "STEER_GATE_FAILED_$stage" } // Never echo URI, nonce, payload, preferences or arbitrary exception text.
    }

    private data class Response(val status: Int, val body: ByteArray, val peerWireSha: String)
    private fun http(run: Run, cm: ConnectivityManager, path: String, payload: ByteArray?,target:String=TARGET,source:String=TUN,hostTarget:String=target): Response {
        run.assertCurrent(cm)
        val until = SystemClock.elapsedRealtime() + 8000
        val socket = admittedSocket(run, cm)
        try {
            val sourceIp=android.net.InetAddresses.parseNumericAddress(source)
            socket.bind(InetSocketAddress(sourceIp, 0)); socket.connect(InetSocketAddress(android.net.InetAddresses.parseNumericAddress(target), 80), 5000)
            require(socket.localAddress==sourceIp)
            val authority=if(':' in hostTarget)"[$hostTarget]" else hostTarget
            val request = "${if (payload == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: $authority\r\nContent-Length: ${payload?.size ?: 0}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); if (payload != null) write(payload); flush() }
            fun next(): Int { val left = until - SystemClock.elapsedRealtime(); check(left > 0); socket.soTimeout = left.coerceAtMost(5000).toInt(); return socket.getInputStream().read() }
            val header = ByteArrayOutputStream()
            while (!header.toString("US-ASCII").endsWith("\r\n\r\n")) { check(header.size() < 4096); val b = next(); check(b >= 0); header.write(b) }
            val text = header.toString("US-ASCII")
            val expected = if (payload == null) "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                else "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
            require(text == expected)
            val body = ByteArray(payload?.size ?: 0) { val b = next(); check(b >= 0); b.toByte() }
            check(next() == -1)
            run.assertCurrent(cm)
            return Response(if (payload == null) 204 else 200, body, hash(byteArrayOf(0, 0) + header.toByteArray() + body))
        } finally { socket.close() }
    }
    private fun admittedSocket(run: Run, cm: ConnectivityManager): Socket {
        val until = SystemClock.elapsedRealtime() + 8000
        while (true) {
            run.assertCurrent(cm)
            val socket = Socket()
            try { run.network.bindSocket(socket); return socket }
            catch (error: java.net.SocketException) {
                socket.close()
                val admission = error.message.orEmpty().let { "EPERM" in it || "ENONET" in it }
                if (!admission || SystemClock.elapsedRealtime() >= until) throw error
                Thread.sleep(100) // Same verified Network only, fixture admission window; no fallback/permission change.
            } catch (error: Throwable) { socket.close(); throw error }
        }
    }

    private class Run(val service: McrfVpnService, val worker: ClientPrimaryWorker, val tun: ParcelFileDescriptor,
                      val platform: Platform, val binder: IBinder, val pid: Int, val birth: String, val network: Network, val physical: Network) {
        fun acks(): Long = (field(platform.javaClass, platform, "debugProtectAndBindAcks") as AtomicLong).get()
        fun lastBoundNetwork(): Long = field(platform.javaClass, platform, "debugLastBoundNetworkHandle") as Long
        fun assertCurrent(cm: ConnectivityManager) {
            require(currentService() === service && field(McrfVpnService::class.java, service, "core") === worker &&
                field(McrfVpnService::class.java, service, "fixtureMode") == true && field(McrfVpnService::class.java, service, "target") == TAG &&
                workers().contains(worker) && worker.state == "STARTING" && binder.isBinderAlive &&
                field(ClientPrimaryWorker::class.java, worker, "hostPlatform") === platform &&
                field(ClientPrimaryHostPlatform::class.java, service, "active") === platform &&
                field(ClientPrimaryHostPlatform::class.java, service, "tunnel") === tun && tun.fileDescriptor.valid() &&
                ownVpn(cm) == network && processBirth(pid) == birth && Os.stat("/proc/$pid").st_uid == Process.myUid())
        }
        companion object {
            fun capture(service: McrfVpnService, worker: ClientPrimaryWorker, cm: ConnectivityManager, app: Context): Run {
                val pid = worker.remotePid
                require(pid > 0 && pid != Process.myPid() && File("/proc/$pid/cmdline").readText().substringBefore('\u0000') == app.packageName + ":primary")
                val value = Run(service, worker, field(ClientPrimaryHostPlatform::class.java, service, "tunnel") as ParcelFileDescriptor,
                    field(ClientPrimaryHostPlatform::class.java, service, "active") as Platform, field(ClientPrimaryWorker::class.java, worker, "remote") as IBinder,
                    pid, processBirth(pid), requireNotNull(ownVpn(cm)), field(ClientPrimaryHostPlatform::class.java, service, "underlying") as Network)
                require(cm.getNetworkCapabilities(value.physical)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true)
                value.assertCurrent(cm); return value
            }
        }
    }
    private fun field(type: Class<*>, target: Any?, name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun currentService(): McrfVpnService? = field(McrfVpnService::class.java, null, "currentService") as McrfVpnService?
    private fun workers(): List<ClientPrimaryWorker> = (field(ClientPrimaryWorker::class.java, null, "slots") as Map<*, *>).values.map { it as ClientPrimaryWorker }
    private fun ownVpn(cm: ConnectivityManager): Network? = cm.allNetworks.filter { n -> cm.getNetworkCapabilities(n)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.ownerUid == Process.myUid()
    } == true && cm.getLinkProperties(n)?.linkAddresses?.any { it.address.hostAddress == TUN } == true }.let { require(it.size <= 1); it.singleOrNull() }
    @Suppress("DEPRECATION") private fun serviceExists(app: Context) = app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any { it.service.className == McrfVpnService::class.java.name }
    private fun requireIdle(app: Context, cm: ConnectivityManager) { require(!serviceExists(app) && currentService() == null && workers().isEmpty() && !ClientPrimaryRetirement.hasPending() &&
        McrfVpnService.status == "Отключено" && McrfVpnService.localProxyPort == 0 && cm.allNetworks.none { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) }
    private fun stopOwned(app: Context, service: McrfVpnService, health: String) {
        if (currentService() !== service) return
        require(field(McrfVpnService::class.java, service, "fixtureMode") == true && field(McrfVpnService::class.java, service, "healthUrl") == health)
        app.startService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
    }
    private fun clean(app: Context, cm: ConnectivityManager, run: Run, before: Set<String>, tuns: Set<String>): Boolean =
        !run.binder.isBinderAlive && processGone(run.pid) && !run.tun.fileDescriptor.valid() && workers().isEmpty() && !serviceExists(app) &&
            currentService() == null && McrfVpnService.status == "Отключено" && McrfVpnService.localProxyPort == 0 && !ClientPrimaryRetirement.hasPending() &&
            cm.allNetworks.none { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } && runtimeNames(app) == before && tunInterfaces(Process.myPid()) == tuns
    private fun processBirth(pid: Int) = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
    private fun processGone(pid: Int): Boolean = try { Os.stat("/proc/$pid"); false } catch (e: ErrnoException) { if (e.errno == OsConstants.ENOENT) true else throw e }
    private fun tunInterfaces(pid: Int): Set<String> = (File("/proc/$pid/fdinfo").listFiles() ?: error("STEER_FDINFO_UNAVAILABLE")).mapNotNull { file ->
        try { file.readLines().firstOrNull { it.startsWith("iff:") }?.substringAfter(':')?.trim() } catch (e: java.io.FileNotFoundException) { null }
    }.toSet()
    private fun runtimeNames(app: Context): Set<String> = (app.cacheDir.list() ?: error("STEER_RUNTIME_UNAVAILABLE")).filter { name ->
        listOf("mcrf-primary-input-", "mcrf-primary-output-", "mcrf-run-", "mcrf-carrier-").any(name::startsWith)
    }.toSet()
    private fun assertMutationBlocked(app: Context) { kernelLock(app, true) }
    private fun assertMutationFree(app: Context) { kernelLock(app, false) }
    private fun kernelLock(app: Context, blocked: Boolean) {
        val file = File(app.noBackupFilesDir.canonicalFile, "engine-update-gate/usage.lock")
        require(file.isFile && !Files.isSymbolicLink(file.toPath()))
        FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock != null) lock.release()
            require((lock == null) == blocked) { "STEER_KERNEL_USAGE_LEASE" }
        }
    }
    private fun await(timeout: Long, condition: () -> Boolean): Boolean { val end = SystemClock.elapsedRealtime() + timeout; do { if (condition()) return true; Thread.sleep(50) } while (SystemClock.elapsedRealtime() < end); return condition() }
    private fun numeric(text: String): InetAddress { val a = text.split('.'); require(a.size == 4 && a.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }); return InetAddress.getByAddress(a.map { it.toInt().toByte() }.toByteArray()) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun privateJson(file: File, root: File): JSONObject { require(file.isFile && file.canonicalFile.parentFile == root && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..65536 && Os.stat(file.path).st_uid == Process.myUid()); return JSONObject(file.readText(Charsets.UTF_8)) }

    /** RAM-only complete encrypted store/preferences comparison; no restore masks service writes. */
    private data class Saved(val generation: String, val stores: Map<String, String>, val prefs: Map<String, Map<String, Any?>>, val files: Map<String, String>) {
        companion object {
            fun capture(app: Context): Saved = synchronized(ProfileStore.storageLock) {
                val preferenceFiles = File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.toList().orEmpty()
                val names = setOf("MainActivity", "engine-updates", "identity", "telegram-service") + preferenceFiles.filter { it.name.endsWith(".xml") || it.name.endsWith(".xml.bak") }.map { it.name.removeSuffix(".bak").removeSuffix(".xml") }
                val prefs = names.associateWith { n -> app.getSharedPreferences(n, Context.MODE_PRIVATE).all.mapValues { (_, v) -> if (v is Set<*>) v.toSet() else v } }
                val savedNames = setOf("profile.enc", "connections.enc", "routing.enc", "preferences.enc", "active-profile.txt")
                val files = linkedMapOf<String, String>()
                fun add(file: File, key: String) { require(file.isFile && !Files.isSymbolicLink(file.toPath())); files[key] = "${file.length()}:${file.lastModified()}:" + hash(file.readBytes()) }
                fun protected(name: String) = savedNames.any { name == it || name == "$it.bak" || name == "$it.new" }
                val roots = requireNotNull(app.filesDir.listFiles())
                roots.filter { protected(it.name) }.forEach { add(it, "files/${it.name}") }
                roots.filter { it.name.matches(Regex("mcrf-store-[0-9a-f-]{36}")) }.forEach { dir -> require(dir.isDirectory && !Files.isSymbolicLink(dir.toPath())); requireNotNull(dir.listFiles()).filter { protected(it.name) }.forEach { add(it, "files/${dir.name}/${it.name}") } }
                preferenceFiles.forEach { add(it, "shared_prefs/${it.name}") }
                Saved(ProfileStore.generation(app), ProfileStore.snapshot(app), prefs, files)
            }
        }
    }
}
