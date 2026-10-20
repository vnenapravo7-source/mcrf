package org.mcrf.client

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
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
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.engine.spi.v1.Platform
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Exclusive, off-main-thread acceptance of the real main VPN and installed sing-box module.
 * The caller supplies a fresh cache/mcrf-test-UUID directory (existing and empty is OK).
 * No consent automation, saved-choice edits, second VPN, public peers, or standalone core.
 * Reflection below only reads private ownership/lifetime evidence; it never installs a hook.
 */
object ClientPrimaryVpnDeviceChecks {
    private const val TUN_ADDRESS = "172.19.0.1"
    private const val DESTINATION = "198.18.0.2"
    private const val DESTINATION_PORT = 80
    private const val DISCONNECTED = "Отключено"
    private val temporaryName = Regex("mcrf-test-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    @JvmStatic fun run(context: Context, temporary: File, passed: Consumer<String>) =
        runChecks(context, temporary, passed, Fault.NONE)

    @JvmStatic fun runRetirement(context: Context, temporary: File, passed: Consumer<String>) =
        runChecks(context, temporary, passed, Fault.RETIREMENT)

    @JvmStatic fun runConnectPending(context: Context, temporary: File, passed: Consumer<String>) =
        runChecks(context, temporary, passed, Fault.CONNECT_PENDING)

    @JvmStatic fun runDestroyedProxyRetirement(context: Context, temporary: File, passed: Consumer<String>) =
        runChecks(context, temporary, passed, Fault.DESTROYED_PROXY)
    @JvmStatic fun runRevoke(context:Context,temporary:File,passed:Consumer<String>) =
        runChecks(context,temporary,passed,Fault.REVOKE)

    @JvmStatic fun runDirect(context:Context,temporary:File,passed:Consumer<String>,inverted:Boolean,negative:Boolean) =
        runChecks(context,temporary,passed,Fault.NONE,inverted to negative)

    @JvmStatic fun runDirectDomain(context:Context,temporary:File,passed:Consumer<String>,inverted:Boolean,negative:Boolean) =
        runChecks(context,temporary,passed,Fault.NONE,inverted to negative,true)

    /** New P1 platform prerequisite only. Reuse the guarded fixture lifetime, not
     * the closed HTTP/Direct/STOP/restart suites. The common owner pins the helper. */
    @JvmStatic fun runOriginalOwner(context:Context,temporary:File,passed:Consumer<String>,helperSha256:String) {
        require(helperSha256.matches(Regex("[0-9a-f]{64}"))) { "Exact ordered common-P1 helper pair pin required" }
        runChecks(context,temporary,passed,Fault.NONE,ownerHelperSha256=helperSha256)
    }

    private enum class Fault { NONE, RETIREMENT, CONNECT_PENDING, DESTROYED_PROXY, REVOKE }

    private fun runChecks(context: Context, temporary: File, passed: Consumer<String>, fault: Fault,directPlan:Pair<Boolean,Boolean>?=null,domainPlan:Boolean=false,ownerHelperSha256:String?=null) {
        val app = context.applicationContext
        check(BuildConfig.DEBUG) { "Primary VPN acceptance requires a DEBUG build with the private test_fixture gate" }
        check(Looper.myLooper() != Looper.getMainLooper() && Application.getProcessName() == app.packageName) {
            "Run primary VPN acceptance off the UI thread in the target application's main process"
        }
        check(fault == Fault.CONNECT_PENDING || VpnService.prepare(app) == null) {
            "Primary VPN acceptance requires VPN consent: approve MCRF in Android before running this check; consent is never bypassed"
        }
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        requireIdle(app, connectivity)
        if(ownerHelperSha256!=null){
            check(ClientP1OwnerFixtureScope.packages(app,ownerHelperSha256)==listOf(app.packageName,"org.mcrf.p1owner","org.mcrf.p1owner.peer"))
            val wrong=(if(ownerHelperSha256[0]=='0')"1" else "0")+ownerHelperSha256.substring(1)
            var rejected=false
            try{ClientP1OwnerFixtureScope.packages(app,wrong)}catch(error:IllegalStateException){
                check(error.message=="P1_OWNER_SCOPE_APK_PIN");rejected=true
            }
            check(rejected)
            passed.accept("p1-owner-scope-only-main-helper-wrong-sha-before-start")
        }
        EngineUpdatePrimary.installation(app, "sing-box") // Read/verify the installed same-signer module; never install it.
        val supplied=temporary.absoluteFile
        val base=app.cacheDir.canonicalFile
        val root = File(base,supplied.name)
        // /data/user/0 and /data/data may be legitimate aliases for Android's cache.
        // Normalize that parent while still rejecting a linked fixture leaf or escape.
        require(temporaryName.matches(root.name) && supplied.parentFile?.canonicalFile == base &&
            supplied.canonicalFile == root && root.canonicalFile == root &&
            !Files.isSymbolicLink(supplied.toPath()) && !Files.isSymbolicLink(root.toPath())) {
            "Primary VPN fixture must be a unique, direct cache/mcrf-test-UUID directory"
        }
        require(!root.exists() || root.isDirectory && root.list()?.isEmpty() == true) {
            "Primary VPN fixture directory must be empty and reserved for this invocation"
        }
        val saved = Saved.capture(app)
        val runtimeBefore = runtimeNames(app)
        val mainTunsBefore = tunInterfaces(Process.myPid())
        val physical = physicalAddress(connectivity)
        val fixture = File(root, "service-fixture.json")
        val owned = mutableListOf<OwnedRun>()
        val connections = mutableListOf<OwnedConnection>()
        var hold: OwnedHold? = null
        var origin: Origin? = null
        var madeDirectory = false
        var madeFixture = false
        var attemptedStart = false
        var failure: Throwable? = null
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        try {
            if (!root.exists()) { check(root.mkdir()); madeDirectory = true }
            Os.chmod(root.path, 0x1c0)
            val peer = Origin(physical, UUID.randomUUID().toString(),directPlan!=null,domainPlan).also { origin = it }
            val tag = "fixture-owned-${peer.nonce}"
            // Pinned sing-box 1.13+ REMOVES overrides on direct outbounds (option/direct.go).
            // RouteActionOptions retains both overrides; exact match + terminal reject prevents
            // unrelated captured traffic from being forwarded to any physical destination.
            val source = if(directPlan!=null)OwnedDirectVpnPlan.build(physical.hostAddress!!,peer.port,tag,directPlan.first,directPlan.second,domainPlan)
                else JSONObject().put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", tag)))
                // ProfileConfig's TUN hijacks DNS before ordinary route rules. Keep even
                // incidental Android DNS local: a hosts-only resolver plus terminal reject.
                .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "hosts").put("tag", "fixture-dns")))
                    .put("final", "fixture-dns").put("rules", JSONArray().put(JSONObject().put("action", "reject"))))
                .put("route", JSONObject().put("final", tag).put("rules", JSONArray()
                    .put(JSONObject().put("inbound", JSONArray().put("tun-in")).put("network", "tcp")
                        .put("ip_cidr", JSONArray().put("$DESTINATION/32")).put("port", DESTINATION_PORT)
                        .put("action", "route").put("outbound", tag)
                        .put("override_address", physical.hostAddress).put("override_port", peer.port))
                    .put(JSONObject().put("action", "reject"))))
            check(fixture.createNewFile()); madeFixture = true
            Os.chmod(fixture.path, 0x180)
            val fixtureData = JSONObject().put("source", source).put("timeout", 3000)
                .put("url", "http://${physical.hostAddress}:${peer.port}${peer.path}/health")
            if(ownerHelperSha256!=null)fixtureData.put("p1OwnerHelperSha256",ownerHelperSha256)
            if (fault != Fault.NONE && fault != Fault.REVOKE) fixtureData.put("retirementFault", true)
            if (fault == Fault.CONNECT_PENDING) fixtureData.put("connectPendingFault", true)
            fixture.writeText(fixtureData.toString(), Charsets.UTF_8)
            if (fault != Fault.NONE && fault != Fault.REVOKE) hold = OwnedHold(root, peer.nonce)

            fun enqueue(vpn: Boolean) {
                val proxyPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                attemptedStart = true
                app.startForegroundService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.START)
                    .putExtra("tun", vpn).putExtra("applyRoutes", false).putExtra("port", proxyPort)
                    .putExtra("test_fixture", fixture.path))
            }

            fun start(submit: Boolean = true): OwnedRun {
                if (submit) {
                    requireIdle(app, connectivity,if(fault==Fault.REVOKE&&owned.isNotEmpty())"VPN-разрешение отозвано"else DISCONNECTED)
                    check(VpnService.prepare(app) == null) { "Primary VPN acceptance requires VPN consent; permission changed before START" }
                    enqueue(true)
                }
                var run: OwnedRun? = null
                check(await(45000) {
                    if (McrfVpnService.status.startsWith("Ошибка")) error(
                        "Main VPN rejected the private fixture: ${McrfVpnService.status}. " +
                            "Use route-action override_address/override_port, never deprecated direct-outbound overrides; no bypass acceptance is allowed")
                    if (!McrfVpnService.status.startsWith("VPN подключён")) false
                    else {
                        run = workers().singleOrNull()?.takeIf { it.choiceId == "singbox" && it.state == "RUNNING" }
                            ?.let { OwnedRun.capture(it, app, source.getJSONObject("route").getString("final")) }
                        run != null
                    }
                }) { "Actual main McrfVpnService did not start its installed sing-box VPN worker: ${McrfVpnService.status}" }
                val actual = requireNotNull(run).also { owned.add(it) }
                var network: Network? = null
                check(await(8000) { network = ownVpnNetwork(connectivity); network != null }) {
                    "No own VPN Network with owner UID ${Process.myUid()} and source TUN address $TUN_ADDRESS"
                }
                actual.network = requireNotNull(network)
                val links = requireNotNull(connectivity.getLinkProperties(actual.network))
                val expectedOwnedDns=setOf(InetAddress.getByName("172.19.0.2"),InetAddress.getByName("fdfe:dcba:9876::2"))
                if(domainPlan)check(links.dnsServers.size==expectedOwnedDns.size&&links.dnsServers.toSet()==expectedOwnedDns) {
                    "Owned domain gate requires exactly both private TUN DNS addresses; never allow a physical resolver"
                }
                actual.tunInterface = requireNotNull(links.interfaceName)
                check(links.routes.any { it.type == android.net.RouteInfo.RTN_UNICAST && it.destination.contains(InetAddress.getByName(DESTINATION)) }) {
                    "Actual VPN has no IPv4 route to $DESTINATION (native inet4/inet6/default-route ambiguity). " +
                        "ProfileConfig replaces source.inbounds: an explicit route_address [$DESTINATION/32] needs a supported private fixture path; never fall back to default/physical HTTP"
                }
                check(tunInterface(Process.myPid(), actual.tun.fd.toString()) == actual.tunInterface &&
                    tunInterfaces(actual.pid).contains(actual.tunInterface)) {
                    "Real TUN FD evidence is required in BOTH main and installed-module worker for ${actual.tunInterface}"
                }
                verify(!McrfVpnService.nativeVpnExclusion && actual.tun.fileDescriptor.valid(),
                    "Main VPN owns a live TUN and transfers its FD to the installed sing-box worker without excluding the client UID")
                return actual
            }

            fun vpnHttp(run: OwnedRun, name: String) {
                run.assertCurrent()
                check(ownVpnNetwork(connectivity) == run.network) { "Owned VPN Network changed before HTTP" }
                if(domainPlan){
                    val host=OwnedDirectVpnPlan.hostname(tag)
                    OwnedTunDnsQuery.lookup(run.network,host,false)
                    OwnedTunDnsQuery.lookup(run.network,"missing-$host",true)
                    run.assertCurrent()
                    verify(true,"Unique owned hostname A answer and unknown-host NXDOMAIN use the actual VPN Network without system-cache/physical fallback")
                }
                if(directPlan?.second==true){
                    val rejectedBefore=peer.proxyRejects.get();var completed=false
                    try {
                        openVpnSocket(run,connectivity).use {socket->
                            socket.bind(InetSocketAddress(InetAddress.getByName(TUN_ADDRESS),0))
                            socket.connect(InetSocketAddress(InetAddress.getByName(DESTINATION),DESTINATION_PORT),8000)
                            check(socket.localAddress.hostAddress==TUN_ADDRESS)
                            exchange(socket,peer,name,if(domainPlan)OwnedDirectVpnPlan.hostname(tag)else DESTINATION);completed=true
                        }
                    }catch(_:java.io.IOException){}catch(_:IllegalStateException){}
                    run.assertCurrent()
                    verify(!completed&&peer.proxyRejects.get()==rejectedBefore+1&&!peer.received(name),
                        "Negative Direct ${if(domainPlan)"domain"else"CIDR"} control uses the actual TUN and reaches exactly one owned refusing proxy hop, never a successful/fallback origin request")
                    return
                }
                val before = TrafficState.current
                openVpnSocket(run,connectivity).use { socket ->
                    // Explicit Network binding AND source binding. Never let a retired Network
                    // silently use the default physical network; never test a localhost target.
                    socket.bind(InetSocketAddress(InetAddress.getByName(TUN_ADDRESS), 0))
                    socket.connect(InetSocketAddress(InetAddress.getByName(DESTINATION), DESTINATION_PORT), 8000)
                    check(socket.localAddress.hostAddress == TUN_ADDRESS) { "HTTP source did not originate on the actual TUN" }
                    // A real domain HTTP request carries its hostname, not the resolved IP.
                    // sing-box HTTP sniffing replaces reverse-mapped metadata.Domain from Host;
                    // the old IP Host erased the owned DNS name before domain rules matched.
                    exchange(socket, peer, name,if(domainPlan)OwnedDirectVpnPlan.hostname(tag)else DESTINATION)
                }
                peer.assertReceived(name)
                if(directPlan!=null)verify(peer.proxyRejects.get()==0,"Compiled Direct ${if(domainPlan)"domain"else"CIDR"} rule reaches the owned origin with no proxy hop on the real TUN")
                verify(await(10000) {
                    run.assertCurrent()
                    val now = TrafficState.current
                    now.available && now.sent >= before.sent + peer.upload.size && now.received >= before.received + peer.download.size
                }, "VPN-bound HTTP $name reaches only the owned physical IPv4 origin and increases actual upload/download counters")
            }

            if (ownerHelperSha256 != null) {
                val run = start()
                run.assertCurrent()
                ClientP1SocketOwnerChecks.run(app, run.network, ownerHelperSha256, passed)
                run.assertCurrent()
                // A live VPN retains Android's system binding: stopService alone
                // cannot destroy it while its TUN remains open. Send the normal
                // STOP only to this still-current, verified owned fixture first.
                app.startService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                verify(awaitLifecycle(25000, "P1 original-owner normal STOP", { run.service }) {
                    clean(app, connectivity, owned, runtimeBefore, mainTunsBefore) &&
                        !ClientPrimaryRetirement.hasPending() && retiringServices().isEmpty()
                }, "P1 original-owner STOP observes actual worker death and TUN service lease retirement")
            } else if (fault == Fault.DESTROYED_PROXY) {
                // A retained VPN TUN can keep Android's system binding alive after
                // stopService. Require actual destruction in a run that never had a TUN.
                enqueue(false)
                var proxy: OwnedConnection? = null
                verify(awaitLifecycle(45000, "Native proxy startup", { currentService() }) {
                    if (McrfVpnService.status.startsWith("Ошибка")) error("Private proxy startup failed: ${McrfVpnService.status}")
                    val service = currentService()
                    if (service == null || !McrfVpnService.status.startsWith("Прокси ") ||
                        field(McrfVpnService::class.java, service, "currentStart") == null) false
                    else {
                        proxy = OwnedConnection.capture(service, tag, hasRun = true)
                        true
                    }
                }, "Installed same-signer sing-box starts an authenticated native proxy run in the actual main service")
                val actual = requireNotNull(proxy).also { connections.add(it) }
                actual.assertProxyCurrent(app)
                verify(ownVpnNetwork(connectivity) == null && tunInterfaces(Process.myPid()) == mainTunsBefore &&
                    tunInterfaces(actual.pid).isEmpty(), "Proxy lifecycle setup owns no main or worker TUN and creates no VPN Network")
                val marker = requireNotNull(hold)
                marker.create()
                app.startService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                var pending: ClientPrimaryRetirement<*>? = null
                verify(awaitLifecycle(10000, "Proxy STOP acknowledgment hold", { actual.service }) {
                    check(McrfVpnService.status != DISCONNECTED) { "Held proxy STOP falsely published Disconnected" }
                    pending = retirement(actual.service)
                    pending?.let { it.owner === actual.worker && it.attempts > 0 && !it.complete &&
                        field(ClientPrimaryHostPlatform::class.java, actual.service, "active") == null } == true
                }, "Real proxy STOP retains its exact native-run owner and main lease in pending retirement without asserting child liveness")
                val batch = requireNotNull(pending)
                actual.assertHeld(app, batch)
                verify(retiredProtectRejected(actual.platform, actual.worker.generation),
                    "Pending proxy STOP denies stale socket protection and blocks the actual exclusive engine mutation lock")
                check(app.stopService(Intent(app, McrfVpnService::class.java))) { "Owned proxy service was not running before destruction" }
                verify(awaitLifecycle(10000, "Actual proxy onDestroy (no TUN/system VPN binding)", { actual.service }) {
                    actual.assertHeld(app, batch)
                    check(ownVpnNetwork(connectivity) == null && tunInterfaces(Process.myPid()) == mainTunsBefore)
                    field(McrfVpnService::class.java, actual.service, "destroyed") == true &&
                        serviceExecutor(actual.service).isShutdown && retiringServices().contains(actual.service)
                }, "Android actually destroys the TUN-free proxy service, shuts down its executor and registers its retiring-service token while parent cleanup stays pending")
                enqueue(true)
                var replacement: McrfVpnService? = null
                verify(awaitLifecycle(10000, "Replacement admission behind destroyed proxy", { currentService() }) {
                    actual.assertHeld(app, batch)
                    val candidate = currentService()
                    if (candidate == null || candidate === actual.service ||
                        field(McrfVpnService::class.java, candidate, "fixtureMode") != true ||
                        (field(McrfVpnService::class.java, candidate, "generation") as Long) == 0L) false
                    else {
                        assertReplacementBlocked(candidate, actual.service, actual.worker, batch)
                        replacement = candidate
                        McrfVpnService.status == "Остановка движка…"
                    }
                }, "A distinct replacement service accepts VPN START but acquires no new core, lease, platform or TUN while the destroyed proxy token remains")
                val waiting = requireNotNull(replacement)
                val attempts = batch.attempts
                verify(awaitLifecycle(12000, "Destroyed proxy retry ownership", { actual.service }) {
                    actual.assertHeld(app, batch)
                    assertReplacementBlocked(waiting, actual.service, actual.worker, batch)
                    check(ownVpnNetwork(connectivity) == null && tunInterfaces(Process.myPid()) == mainTunsBefore)
                    check(retiredProtectRejected(actual.platform, actual.worker.generation))
                    batch.attempts >= attempts + 2
                }, "The destroyed proxy's surviving retry loop retains its exact core and main lease across failures and prevents early replacement native START")
                marker.release()
                val replacementVpn = start(submit = false)
                check(replacementVpn.service === waiting && replacementVpn.service !== actual.service)
                check(replacementVpn.pid != actual.pid || replacementVpn.birth != actual.birth) { "Replacement reused the retired proxy process" }
                verify(awaitLifecycle(10000, "Destroyed proxy death and lease cleanup", { actual.service }) {
                    actual.gone() && batch.complete && serviceExecutor(actual.service).isTerminated &&
                        !retiringServices().contains(actual.service) && !ClientPrimaryRetirement.hasPending() &&
                        replacementVpn.mainUsage !== actual.mainUsage
                }, "Releasing only the owned marker observes proxy Binder/process death and exact lease cleanup, drains the destroyed-service token and admits the fresh VPN")
                vpnHttp(replacementVpn, "tun-after-retirement")
                replacementVpn.assertCurrent()
                app.startService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                verify(awaitLifecycle(25000, "Replacement VPN normal STOP", { replacementVpn.service }) {
                    clean(app, connectivity, owned, runtimeBefore, mainTunsBefore) && actual.gone() &&
                        !ClientPrimaryRetirement.hasPending() && retiringServices().isEmpty() && usageClosed(replacementVpn.mainUsage)
                }, "Fresh replacement VPN normal STOP observes actual worker death, closes TUN/leases and restores runtime and FD baselines")
                verify(retiredProtectRejected(replacementVpn), "Replacement VPN STOP denies its own stale protect generation")
            } else if (fault == Fault.CONNECT_PENDING) {
                val marker = requireNotNull(hold)
                marker.create()
                enqueue(false)
                var adopted: OwnedConnection? = null
                verify(await(45000) {
                    val service = currentService()
                    val retirement = service?.let(::retirement)
                    if (service == null || retirement == null || retirement.owner == null || retirement.attempts == 0 ||
                        field(ClientPrimaryHostPlatform::class.java, service, "active") != null) false
                    else {
                        check(McrfVpnService.status != DISCONNECTED) { "Pending authenticated connection falsely published Disconnected" }
                        adopted = OwnedConnection.capture(service, tag)
                        true
                    }
                }, "CleanupPending adopts the exact authenticated sing-box connection into the real service retirement before RUN")
                val actual = requireNotNull(adopted).also { connections.add(it) }
                val batch = requireNotNull(retirement(actual.service))
                val attempts = batch.attempts
                verify(awaitLifecycle(10000, "Authenticated connect acknowledgment hold", { actual.service }) {
                    actual.assertHeld(app, batch)
                    check(!serviceExecutor(actual.service).isShutdown) { "Pending connect unexpectedly destroyed its service" }
                    check(ownVpnNetwork(connectivity) == null && tunInterfaces(Process.myPid()) == mainTunsBefore) { "Connect fault issued native VPN RUN" }
                    batch.attempts >= attempts + 2
                }, "Authenticated connect failure retains its exact core and main usage lease across retries, blocks mutation, and never issues RUN or creates a TUN")
                verify(retiredProtectRejected(actual.platform, actual.worker.generation),
                    "Pending authenticated connect retires the actual platform and rejects stale socket protection")
                marker.release()
                verify(await(25000) {
                    actual.gone() && batch.complete && serviceExecutor(actual.service).isTerminated &&
                        !ClientPrimaryRetirement.hasPending() && retiringServices().isEmpty() &&
                        clean(app, connectivity, owned, runtimeBefore, mainTunsBefore, requireDisconnected = false)
                }, "Releasing only the owned connect marker observes actual Binder/process death and drains the adopted core, main lease and runtime files")
            } else {
            val first = start()
            first.assertCurrent()
            Socket().use { socket ->
                socket.bind(InetSocketAddress(0)) // Materialize the socket FD before lending a duplicate.
                ParcelFileDescriptor.fromSocket(socket).use { borrowed ->
                    check(first.platform.protectAndBind(first.worker.generation, borrowed.fd)) {
                        "Actual main-service protectAndBind rejected a live owned socket"
                    }
                }
                socket.connect(InetSocketAddress(physical, peer.port), 8000)
                exchange(socket, peer, "protected")
            }
            peer.assertReceived("protected")
            passed.accept("Actual main-service protectAndBind protects/binds a real socket and completes HTTP to the owned origin")
            vpnHttp(first, "tun-first")
            vpnHttp(first, "tun-second")
            if(directPlan?.second!=true)verifyNotification(app,passed)
            first.assertCurrent()
            if (fault == Fault.RETIREMENT) requireNotNull(hold).create()
            if(fault==Fault.REVOKE)first.service.onRevoke()
            else app.startService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
            if (fault == Fault.RETIREMENT) {
                var pending: ClientPrimaryRetirement<*>? = null
                verify(awaitLifecycle(10000, "TUN STOP acknowledgment hold", { first.service }) {
                    check(McrfVpnService.status != DISCONNECTED) { "Held STOP falsely published Disconnected" }
                    pending = retirement(first.service)
                    pending?.let { it.owner === first.worker && it.attempts > 0 && !it.complete &&
                        field(ClientPrimaryHostPlatform::class.java, first.service, "active") == null } == true
                }, "Actual STOP reaches the parent acknowledgment fault with the exact native worker retained in retirement")
                val batch = requireNotNull(pending)
                first.assertHeld(app, batch)
                verify(retiredProtectRejected(first), "Held STOP retires the actual platform and denies stale protect callbacks while retaining the parent TUN and lease")
                // STOP with a retained real TUN tests same-instance START queueing.
                // A requested stopService is not evidence of Android onDestroy.
                val stoppedEpoch = field(McrfVpnService::class.java, first.service, "epoch") as Long
                val heldGeneration = field(McrfVpnService::class.java, first.service, "generation") as Long
                enqueue(true)
                var queuedEpoch = stoppedEpoch
                verify(awaitLifecycle(10000, "Same-instance VPN START queueing", { first.service }) {
                    first.assertHeld(app, batch)
                    check(currentService() === first.service) { "Held TUN START unexpectedly changed service instance" }
                    queuedEpoch = field(McrfVpnService::class.java, first.service, "epoch") as Long
                    if (queuedEpoch <= stoppedEpoch) false
                    else {
                        check(queuedEpoch == stoppedEpoch + 1) { "An unrelated command superseded the owned queued START" }
                        assertSameInstanceBlocked(first, batch, heldGeneration, queuedEpoch)
                        true
                    }
                }, "VPN START advances the same actual service's epoch and remains queued behind the exact held TUN/core/main lease")
                val attempts = batch.attempts
                verify(awaitLifecycle(12000, "Same-instance held VPN retries", { first.service }) {
                    first.assertHeld(app, batch)
                    assertSameInstanceBlocked(first, batch, heldGeneration, queuedEpoch)
                    check(retiredProtectRejected(first)) { "Queued START revived the old protect generation" }
                    batch.attempts >= attempts + 2
                }, "Repeated parent acknowledgment failures retain exact original resources and deny stale protect calls without admitting a second lease or native START")
                requireNotNull(hold).release()
                val second = start(submit = false)
                check(second.service === first.service && second.mainUsage !== first.mainUsage) { "Queued VPN did not restart in the same service with a fresh main lease" }
                check(field(McrfVpnService::class.java, second.service, "epoch") == queuedEpoch) { "Unexpected START superseded the owned queued epoch" }
                check(first.pid != second.pid || first.birth != second.birth) { "Replacement reused the retired worker process" }
                verify(awaitLifecycle(10000, "Same-instance old VPN death and FD cleanup", { first.service }) {
                    first.gone(requireInterfaceGone = false, requireServiceEmpty = false) && batch.complete && usageClosed(first.mainUsage) &&
                        field(McrfVpnService::class.java, second.service, "core") === second.worker &&
                        field(McrfVpnService::class.java, second.service, "engineUsage") === second.mainUsage &&
                        field(McrfVpnService::class.java, second.service, "destroyed") == false &&
                        !serviceExecutor(second.service).isShutdown && retiringServices().isEmpty() &&
                        !ClientPrimaryRetirement.hasPending()
                }, "Marker release observes old Binder/process death and closes the exact original TUN/lease before the queued epoch runs a fresh worker in the same service")
                vpnHttp(second, "tun-after-retirement")
                second.assertCurrent()
                app.startService(Intent(app, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                verify(awaitLifecycle(25000, "Queued VPN normal STOP", { second.service }) {
                    clean(app, connectivity, owned, runtimeBefore, mainTunsBefore) && !ClientPrimaryRetirement.hasPending() && retiringServices().isEmpty()
                }, "Queued VPN normal STOP observes worker death and complete TUN, lease, service and runtime cleanup")
                verify(retiredProtectRejected(second), "Queued VPN STOP rejects its own stale protect generation")
            } else {
            verify(await(20000) { clean(app, connectivity, owned, runtimeBefore, mainTunsBefore,requireDisconnected=fault!=Fault.REVOKE) &&
                (fault!=Fault.REVOKE||(McrfVpnService.status=="VPN-разрешение отозвано"&&!ClientPrimaryRetirement.hasPending()&&retiringServices().isEmpty()&&serviceExecutor(first.service).isTerminated)) },
                if(fault==Fault.REVOKE)"Actual owned onRevoke callback observes worker death, closes both TUN FDs/VPN Network and runtime, retaining the revoked status"
                else "STOP observes Binder/process death, closes main and worker TUN ownership, removes the VPN/interface, and cleans private runtime files")
            verify(retiredProtectRejected(first), if(fault==Fault.REVOKE)"onRevoke retires the actual platform and rejects stale protect callbacks"else "STOP retires the actual main platform: a stale protect callback is rejected")

            val second = start()
            check(first.pid != second.pid || first.birth != second.birth) { "Restart reused a surviving primary worker" }
            vpnHttp(second, if(fault==Fault.REVOKE)"tun-after-revoke-callback"else"tun-before-death")
            second.assertCurrent()
            second.verifyProcess(app)
            if(fault==Fault.REVOKE){
                // Invoke the real callback only on this fixture's service. Do not revoke
                // Android's user's VPN consent or change any global network setting.
                check(VpnService.prepare(app)==null){"User VPN consent changed during local callback fixture"}
                app.startService(Intent(app,McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                verify(await(20000){clean(app,connectivity,owned,runtimeBefore,mainTunsBefore)},"Fresh VPN after owned onRevoke callback passes HTTP then STOP cleans all actual resources; Android consent was never revoked")
                verify(retiredProtectRejected(second),"Post-revoke replacement STOP rejects its own retired protect generation")
            }else{
                // Only this invocation's verified same-UID :primary PID/birth. No package force-stop.
                Process.killProcess(second.pid)
                verify(await(20000) { clean(app, connectivity, owned, runtimeBefore, mainTunsBefore) },
                    "Unexpected death of the owned primary worker makes the main service stop and releases the real TUN, VPN Network and runtime files")
                verify(retiredProtectRejected(second), "Worker death retires the actual main platform and rejects stale protect callbacks")
            }
            }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            // A finally must still drain after an interrupted HTTP/wait. Preserve the signal.
            val interrupted = Thread.interrupted()
            fun finish(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error else previous.addSuppressed(error)
                }
            }
            // Release only our inode/nonce marker BEFORE stopping or awaiting any
            // service: otherwise a failed assertion would strand the real retry loop.
            finish { hold?.release() }
            finish {
                // stopService never creates a new non-fixture service or sends STOP to one
                // already destroyed on a startup failure (which would write wantedRunning).
                if (attemptedStart) app.stopService(Intent(app, McrfVpnService::class.java))
            }
            var cleaned = !attemptedStart
            finish {
                cleaned = await(25000) {
                    clean(app, connectivity, owned, runtimeBefore, mainTunsBefore, requireDisconnected = false) &&
                        connections.all { it.gone() && serviceExecutor(it.service).isTerminated } && (fault == Fault.NONE ||
                        !ClientPrimaryRetirement.hasPending() && retiringServices().isEmpty() &&
                        owned.all { usageClosed(it.mainUsage) && serviceExecutor(it.service).isTerminated })
                }
                check(cleaned) { "Primary VPN cleanup incomplete: actual worker/TUN/service/runtime teardown was not observed; " +
                    lifecycleState(currentService()) + "; owned=" +
                    (owned.map { it.service } + connections.map { it.service }).distinct().joinToString { lifecycleState(it) } }
            }
            finish { origin?.close() }
            finish {
                check(Saved.capture(app) == saved) { "Primary VPN acceptance changed saved ProfileStore generation/content/files or full user preferences; no restore/write is attempted" }
                passed.accept("All saved profiles, catalogue, routes, encrypted settings and full preferences remain unchanged")
            }
            if (cleaned) finish {
                if (madeFixture) {
                    check(root.canonicalFile == root && fixture.canonicalFile.parentFile == root && !Files.isSymbolicLink(fixture.toPath()))
                    check(!fixture.exists() || fixture.delete()) { "Could not remove the exact owned service fixture" }
                }
                if (madeDirectory) check(root.delete()) { "Owned fixture directory is not empty; refusing recursive deletion" }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw it }
    }

    private fun await(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (predicate()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(50)
        } while (true)
    }

    private fun awaitLifecycle(timeoutMillis: Long, phase: String, service: () -> McrfVpnService?, predicate: () -> Boolean): Boolean {
        fun evidence(): String {
            val observed = service()
            val current = currentService()
            return lifecycleState(observed) + if (current !== observed) "; replacement/current=" + lifecycleState(current) else ""
        }
        try {
            check(await(timeoutMillis, predicate)) {
                "$phase timed out; ${evidence()}. stopService requests stopping; only observed destroyed/executor/token fields establish onDestroy."
            }
            return true
        } catch (error: Throwable) {
            error.addSuppressed(IllegalStateException("$phase evidence: ${evidence()}"))
            throw error
        }
    }

    /** Snapshot before finally releases the marker; log identities/booleans, never profile or preference contents. */
    private fun lifecycleState(service: McrfVpnService?): String = try {
        fun id(value: Any?) = value?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "null"
        val current = currentService()
        val global = "current=${id(current)},globalPending=${ClientPrimaryRetirement.hasPending()},retiringCount=${retiringServices().size},status=${McrfVpnService.status}"
        if (service == null) global else {
            val core = field(McrfVpnService::class.java, service, "core") as ClientPrimaryWorker?
            val usage = field(McrfVpnService::class.java, service, "engineUsage") as EngineUpdateGate.Usage?
            val tun = field(ClientPrimaryHostPlatform::class.java, service, "tunnel") as ParcelFileDescriptor?
            val batch = retirement(service)
            val marker = field(McrfVpnService::class.java, service, "fixtureRetirementHold") as File?
            "$global,service=${id(service)},currentSame=${current === service},destroyed=${field(McrfVpnService::class.java, service, "destroyed")}," +
                "executorShutdown=${serviceExecutor(service).isShutdown},executorTerminated=${serviceExecutor(service).isTerminated}," +
                "retiringToken=${retiringServices().contains(service)},epoch=${field(McrfVpnService::class.java, service, "epoch")}," +
                "generation=${field(McrfVpnService::class.java, service, "generation")},core=${id(core)},pid=${core?.remotePid}," +
                "workerState=${core?.state},binderAlive=${core?.let { (field(ClientPrimaryWorker::class.java, it, "remote") as IBinder?)?.isBinderAlive }}," +
                "deathLatch=${core?.let { (field(ClientPrimaryWorker::class.java, it, "dead") as CountDownLatch).count }}," +
                "mainLease=${id(usage)},mainLeaseClosed=${usage?.let(::usageClosed)},tun=${id(tun)},tunValid=${tun?.fileDescriptor?.valid()}," +
                "activePlatform=${id(field(ClientPrimaryHostPlatform::class.java, service, "active"))}," +
                "batch=${id(batch)},batchOwnerSame=${batch != null && batch.owner === core},attempts=${batch?.attempts},complete=${batch?.complete},markerExists=${marker?.exists()}"
        }
    } catch (error: Exception) { "Lifecycle snapshot unavailable: ${error.javaClass.simpleName}" }

    private fun openVpnSocket(run:OwnedRun,connectivity:ConnectivityManager):Socket {
        val deadline=SystemClock.elapsedRealtime()+8000
        while(true) {
            run.assertCurrent()
            check(ownVpnNetwork(connectivity)==run.network) {"Owned VPN changed before socket admission"}
            val socket=Socket()
            try {
                run.network.bindSocket(socket)
                return socket
            } catch(error:java.net.SocketException) {
                socket.close()
                // NetworkAgent visibility can precede netd UID admission. Retry only
                // this unchanged, verified VPN; never select a physical/default network.
                val transient=error.message.orEmpty().let {"EPERM" in it || "ENONET" in it}
                if(!transient || SystemClock.elapsedRealtime()>=deadline)throw error
                Thread.sleep(100)
            } catch(error:Throwable) {socket.close();throw error}
        }
    }

    private fun field(type: Class<*>, target: Any?, name: String): Any? =
        type.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun currentService(): McrfVpnService? =
        field(McrfVpnService::class.java, null, "currentService") as McrfVpnService?

    private fun retirement(service: McrfVpnService): ClientPrimaryRetirement<*>? =
        field(McrfVpnService::class.java, service, "retirement") as ClientPrimaryRetirement<*>?

    private fun retiringServices(): Set<*> =
        field(McrfVpnService::class.java, null, "retiringServices") as Set<*>

    private fun serviceExecutor(service: McrfVpnService): ExecutorService =
        field(McrfVpnService::class.java, service, "worker") as ExecutorService

    private fun usageClosed(usage: EngineUpdateGate.Usage): Boolean =
        (field(EngineUpdateGate.Usage::class.java, usage, "closed") as AtomicBoolean).get()

    /** Try only the existing kernel lock; never begin a mutation or write a pending receipt. */
    private fun assertMutationBlocked(context: Context) {
        val lock = File(context.noBackupFilesDir.canonicalFile, "engine-update-gate/usage.lock")
        check(lock.isFile && !Files.isSymbolicLink(lock.toPath())) { "Actual engine usage lock is absent" }
        FileChannel.open(lock.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val exclusive = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (exclusive != null) {
                exclusive.release()
                error("Retained main usage lease did not block the actual exclusive mutation lock")
            }
        }
    }

    private fun assertHeld(context: Context, service: McrfVpnService, worker: ClientPrimaryWorker,
                           usage: EngineUpdateGate.Usage, batch: ClientPrimaryRetirement<*>) {
        check(retirement(service) === batch && batch.owner === worker && !batch.complete && batch.attempts > 0 &&
            ClientPrimaryRetirement.hasPending() && field(McrfVpnService::class.java, service, "core") === worker &&
            field(McrfVpnService::class.java, service, "engineUsage") === usage && !usageClosed(usage) &&
            field(McrfVpnService::class.java, service, "fixtureMode") == true &&
            (field(ClientPrimaryWorker::class.java, worker, "stopping") as AtomicBoolean).get() &&
            field(ClientPrimaryHostPlatform::class.java, service, "active") == null && McrfVpnService.status != DISCONNECTED) {
            "Held parent acknowledgment lost its exact core/main lease, callback denial, pending barrier or non-Disconnected state"
        }
        val marker = field(McrfVpnService::class.java, service, "fixtureRetirementHold") as File
        check(marker.isFile && !Files.isSymbolicLink(marker.toPath())) { "Owned parent acknowledgment marker disappeared" }
        assertMutationBlocked(context)
    }

    private fun assertSameInstanceBlocked(old: OwnedRun, batch: ClientPrimaryRetirement<*>, generation: Long, epoch: Long) {
        check(currentService() === old.service && field(McrfVpnService::class.java, old.service, "destroyed") == false &&
            !serviceExecutor(old.service).isShutdown && retiringServices().isEmpty() &&
            retirement(old.service) === batch && !batch.complete && ClientPrimaryRetirement.hasPending() &&
            field(McrfVpnService::class.java, old.service, "core") === old.worker &&
            field(McrfVpnService::class.java, old.service, "engineUsage") === old.mainUsage && !usageClosed(old.mainUsage) &&
            field(McrfVpnService::class.java, old.service, "generation") == generation &&
            field(McrfVpnService::class.java, old.service, "epoch") == epoch &&
            field(McrfVpnService::class.java, old.service, "runningConfig") == null &&
            field(McrfVpnService::class.java, old.service, "currentStart") == null &&
            field(ClientPrimaryHostPlatform::class.java, old.service, "active") == null &&
            workers().all { it === old.worker }) {
            "Queued VPN START changed the old service/core/main lease or reached a new native generation before parent acknowledgment"
        }
    }

    private fun assertReplacementBlocked(replacement: McrfVpnService, oldService: McrfVpnService,
                                         oldWorker: ClientPrimaryWorker, batch: ClientPrimaryRetirement<*>) {
        check(currentService() === replacement && replacement !== oldService && retiringServices().contains(oldService) &&
            field(McrfVpnService::class.java, oldService, "destroyed") == true && serviceExecutor(oldService).isShutdown &&
            retirement(oldService) === batch && !batch.complete && ClientPrimaryRetirement.hasPending() &&
            field(McrfVpnService::class.java, replacement, "core") == null &&
            field(McrfVpnService::class.java, replacement, "engineUsage") == null &&
            field(McrfVpnService::class.java, replacement, "runningConfig") == null &&
            field(McrfVpnService::class.java, replacement, "currentStart") == null &&
            field(McrfVpnService::class.java, replacement, "target") == "" &&
            field(ClientPrimaryHostPlatform::class.java, replacement, "active") == null &&
            field(ClientPrimaryHostPlatform::class.java, replacement, "tunnel") == null &&
            McrfVpnService.localProxyPort == 0 && workers().all { it === oldWorker }) {
            "Replacement passed the old retiring-service barrier and admitted native work before acknowledgment"
        }
    }

    private fun workers(): List<ClientPrimaryWorker> =
        (field(ClientPrimaryWorker::class.java, null, "slots") as Map<*, *>).values.map { it as ClientPrimaryWorker }

    @Suppress("DEPRECATION")
    private fun serviceExists(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == McrfVpnService::class.java.name }

    private fun primaryProcesses(context: Context): List<ActivityManager.RunningAppProcessInfo> =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().filter {
            it.uid == Process.myUid() && it.processName in listOf(":primary", ":primaryProbe", ":primaryWarp").map { suffix -> context.packageName + suffix }
        }

    private fun verifyNotification(context:Context,passed:Consumer<String>){
        if(android.os.Build.VERSION.SDK_INT>=33)check(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED){"Notification acceptance requires existing user notification consent; never grant it automatically"}
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        fun owned()=manager.activeNotifications.firstOrNull {it.id==1&&it.packageName==context.packageName}?.notification
        check(await(8000){val notice=owned();val expected="MCRF · ${TrafficState.current.server.ifEmpty {"Подключение"}}"
            notice!=null&&notice.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()==expected&&notice.flags and android.app.Notification.FLAG_ONGOING_EVENT!=0}){"Actual ongoing notification did not show current runtime server"}
        passed.accept("Actual posted ongoing notification names the same current server as live runtime state")
        check(await(8000){val notice=owned();val state=TrafficState.current;val text=notice?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            val expanded=notice?.extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            state.available&&(state.sent>0||state.received>0)&&text==TrafficState.notificationTraffic(state)&&!text.contains("/с")&&!text.contains("/s")&&!expanded.contains("/с")&&!expanded.contains("/s")}){"Actual foreground notification must show real combined traffic, never speeds"}
        passed.accept("Actual foreground notification shows measured combined session traffic, without speed in collapsed or expanded text")
    }
    private fun requireIdle(context: Context, connectivity: ConnectivityManager,expectedStatus:String=DISCONNECTED) {
        check(!serviceExists(context) && workers().isEmpty() && primaryProcesses(context).isEmpty() &&
            !ClientPrimaryRetirement.hasPending() && retiringServices().isEmpty() &&
            McrfVpnService.status == expectedStatus && McrfVpnService.localProxyPort == 0 &&
            connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
            "Primary VPN acceptance requires an idle client, no VPN and no active primary/probe worker; never interrupt a user connection or another device check"
        }
    }

    private fun physicalAddress(connectivity: ConnectivityManager): Inet4Address {
        val candidates = connectivity.allNetworks.filter { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.sortedBy { if (it == connectivity.activeNetwork) 0 else 1 }
        return candidates.firstNotNullOfOrNull { network ->
            val links = connectivity.getLinkProperties(network) ?: return@firstNotNullOfOrNull null
            val device = links.interfaceName?.let(NetworkInterface::getByName) ?: return@firstNotNullOfOrNull null
            if (!device.isUp || device.isLoopback) return@firstNotNullOfOrNull null
            links.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull {
                !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress &&
                    device.inetAddresses.toList().contains(it) && it.hostAddress != TUN_ADDRESS && it.hostAddress != DESTINATION
            }
        } ?: error("Primary VPN acceptance requires a currently assigned phone physical IPv4 address; no external origin is permitted")
    }

    private fun ownVpnNetwork(connectivity: ConnectivityManager): Network? = connectivity.allNetworks.filter { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true && caps.ownerUid == Process.myUid() &&
            connectivity.getLinkProperties(network)?.linkAddresses?.any { it.address.hostAddress == TUN_ADDRESS } == true
    }.let { matches -> check(matches.size <= 1) { "More than one owned fixture VPN Network" }; matches.singleOrNull() }

    private class OwnedRun(val worker: ClientPrimaryWorker, val platform: Platform, val service: McrfVpnService,
                           val tun: ParcelFileDescriptor, val binder: IBinder, val pid: Int, val birth: String) {
        val mainUsage = field(McrfVpnService::class.java, service, "engineUsage") as EngineUpdateGate.Usage
        private val originalTunFd = tun.fd
        private val originalTunStat = Os.fstat(tun.fileDescriptor)
        lateinit var network: Network
        var tunInterface = ""
        fun assertCurrent() {
            check(workers().contains(worker) && worker.state == "RUNNING" && binder.isBinderAlive &&
                field(McrfVpnService::class.java, service, "core") === worker &&
                field(McrfVpnService::class.java, service, "fixtureMode") == true &&
                field(ClientPrimaryHostPlatform::class.java, service, "active") === platform &&
                field(ClientPrimaryHostPlatform::class.java, service, "tunnel") === tun && tun.fileDescriptor.valid()) {
                "The actual fixture service/worker/TUN generation changed; no substitute worker is accepted"
            }
        }
        fun verifyProcess(context: Context) {
            check(pid > 0 && pid != Process.myPid() && Os.stat("/proc/$pid").st_uid == Process.myUid() &&
                File("/proc/$pid/cmdline").readText().substringBefore('\u0000') == context.packageName + ":primary" &&
                processBirth(pid) == birth) { "Owned primary worker PID/UID/process/birth verification failed" }
        }
        fun assertHeld(context: Context, batch: ClientPrimaryRetirement<*>) {
            ClientPrimaryVpnDeviceChecks.assertHeld(context, service, worker, mainUsage, batch)
            check(field(ClientPrimaryHostPlatform::class.java, service, "tunnel") === tun && tun.fileDescriptor.valid() &&
                tun.fd == originalTunFd && Os.fstat(tun.fileDescriptor).let {
                    it.st_dev == originalTunStat.st_dev && it.st_ino == originalTunStat.st_ino
                } && tunInterface(Process.myPid(), originalTunFd.toString()) == tunInterface) {
                "Held retirement lost or replaced the exact original main-process TUN FD"
            }
        }
        fun gone(requireInterfaceGone: Boolean = true, requireServiceEmpty: Boolean = true): Boolean = !binder.isBinderAlive && processGone(pid) &&
            (field(ClientPrimaryWorker::class.java, worker, "dead") as CountDownLatch).count == 0L &&
            !workers().contains(worker) && !tun.fileDescriptor.valid() &&
            usageClosed(field(ClientPrimaryWorker::class.java, worker, "usage") as EngineUpdateGate.Usage) &&
            (!requireServiceEmpty || field(ClientPrimaryHostPlatform::class.java, service, "tunnel") == null &&
                field(ClientPrimaryHostPlatform::class.java, service, "active") == null) &&
            (!requireInterfaceGone || tunInterface.isEmpty() || NetworkInterface.getByName(tunInterface) == null)

        companion object {
            fun capture(worker: ClientPrimaryWorker, context: Context, tag: String): OwnedRun {
                val platform = field(ClientPrimaryWorker::class.java, worker, "hostPlatform") as Platform
                val service = field(platform.javaClass, platform, "this\$0") as McrfVpnService
                check(field(McrfVpnService::class.java, service, "target") == tag) { "Main service did not use this invocation's fixture" }
                val run = OwnedRun(worker, platform, service,
                    field(ClientPrimaryHostPlatform::class.java, service, "tunnel") as ParcelFileDescriptor,
                    field(ClientPrimaryWorker::class.java, worker, "remote") as IBinder, worker.remotePid, processBirth(worker.remotePid))
                run.assertCurrent(); run.verifyProcess(context)
                return run
            }
        }
    }

    /** Connect may already have stopped its child by the time we observe adoption.
     * Retain the authenticated parent's recorded PID/birth; never claim a live child.
     */
    private class OwnedConnection(val worker: ClientPrimaryWorker, val service: McrfVpnService,
                                  val platform: Platform, val binder: IBinder, val pid: Int, val birth: String,
                                  val mainUsage: EngineUpdateGate.Usage, private val hasRun: Boolean) {
        fun assertProxyCurrent(context: Context) {
            check(hasRun && worker.state == "RUNNING" && binder.isBinderAlive && workers().contains(worker) &&
                field(McrfVpnService::class.java, service, "core") === worker &&
                field(McrfVpnService::class.java, service, "engineUsage") === mainUsage && !usageClosed(mainUsage) &&
                field(ClientPrimaryHostPlatform::class.java, service, "active") === platform &&
                field(ClientPrimaryHostPlatform::class.java, service, "tunnel") == null &&
                field(ClientPrimaryHostPlatform::class.java, service, "vpnMode") == false &&
                field(McrfVpnService::class.java, service, "currentStart") != null &&
                (field(ClientPrimaryWorker::class.java, worker, "started") as AtomicBoolean).get() &&
                pid > 0 && pid != Process.myPid() && Os.stat("/proc/$pid").st_uid == Process.myUid() &&
                File("/proc/$pid/cmdline").readText().substringBefore('\u0000') == context.packageName + ":primary" &&
                processBirth(pid) == birth) { "Actual authenticated native proxy ownership/PID/birth or TUN-free mode changed before STOP" }
        }
        fun assertHeld(context: Context, batch: ClientPrimaryRetirement<*>) {
            ClientPrimaryVpnDeviceChecks.assertHeld(context, service, worker, mainUsage, batch)
            check(field(ClientPrimaryWorker::class.java, worker, "handshake") == true &&
                worker.remotePid == pid && field(ClientPrimaryWorker::class.java, worker, "processBirth") == birth &&
                (field(ClientPrimaryWorker::class.java, worker, "started") as AtomicBoolean).get() == hasRun &&
                (hasRun || (field(ClientPrimaryWorker::class.java, worker, "directories") as Set<*>).isEmpty()) &&
                field(McrfVpnService::class.java, service, "fixtureConnectPending") == false &&
                field(ClientPrimaryHostPlatform::class.java, service, "tunnel") == null &&
                field(McrfVpnService::class.java, service, "currentStart") == null) {
                "Held proxy/connect fault lost its authenticated exact owner or changed whether native RUN occurred"
            }
        }
        fun gone(): Boolean = !binder.isBinderAlive && processGone(pid) &&
            (field(ClientPrimaryWorker::class.java, worker, "dead") as CountDownLatch).count == 0L &&
            !workers().contains(worker) && usageClosed(mainUsage) &&
            usageClosed(field(ClientPrimaryWorker::class.java, worker, "usage") as EngineUpdateGate.Usage) &&
            field(McrfVpnService::class.java, service, "core") == null &&
            field(McrfVpnService::class.java, service, "engineUsage") == null &&
            field(ClientPrimaryHostPlatform::class.java, service, "active") == null &&
            field(ClientPrimaryHostPlatform::class.java, service, "tunnel") == null &&
            (field(ClientPrimaryWorker::class.java, worker, "started") as AtomicBoolean).get() == hasRun

        companion object {
            fun capture(service: McrfVpnService, tag: String, hasRun: Boolean = false): OwnedConnection {
                check(field(McrfVpnService::class.java, service, "fixtureMode") == true &&
                    field(McrfVpnService::class.java, service, "target") == tag)
                val worker = field(McrfVpnService::class.java, service, "core") as ClientPrimaryWorker
                check(worker.choiceId == "singbox" && worker.generation > 0 &&
                    field(ClientPrimaryWorker::class.java, worker, "handshake") == true &&
                    field(McrfVpnService::class.java, service, "fixtureConnectPending") == false &&
                    (field(ClientPrimaryWorker::class.java, worker, "started") as AtomicBoolean).get() == hasRun) {
                    "Actual authenticated connect/native RUN state differs from the requested device check"
                }
                val platform = field(ClientPrimaryWorker::class.java, worker, "hostPlatform") as Platform
                check(field(platform.javaClass, platform, "this\$0") === service)
                val pid = worker.remotePid
                val birth = field(ClientPrimaryWorker::class.java, worker, "processBirth") as String
                check(pid > 0 && pid != Process.myPid() && birth.isNotEmpty())
                return OwnedConnection(worker, service, platform,
                    field(ClientPrimaryWorker::class.java, worker, "remote") as IBinder, pid, birth,
                    field(McrfVpnService::class.java, service, "engineUsage") as EngineUpdateGate.Usage, hasRun)
            }
        }
    }

    /** An exclusive, private marker owned by inode and nonce; never remove another run's file. */
    private class OwnedHold(private val root: File, private val nonce: String) {
        private val file = File(root, "retirement-hold")
        private var identity: android.system.StructStat? = null
        private var sealed = false

        fun create() {
            check(identity == null && root.canonicalFile == root && file.canonicalFile.parentFile == root)
            val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180)
            try {
                identity = Os.fstat(fd)
                val bytes = nonce.toByteArray(Charsets.US_ASCII)
                var written = 0
                while (written < bytes.size) {
                    val count = Os.write(fd, bytes, written, bytes.size - written)
                    check(count > 0); written += count
                }
                sealed = true
                check(Os.fstat(fd).st_mode and 0x1ff == 0x180) { "Acknowledgment marker is not private 0600" }
            } finally { Os.close(fd) }
        }

        fun release() {
            val owned = identity ?: return
            check(root.canonicalFile == root && file.canonicalFile.parentFile == root && !Files.isSymbolicLink(file.toPath()))
            val actual = Os.lstat(file.path)
            check(actual.st_dev == owned.st_dev && actual.st_ino == owned.st_ino && actual.st_uid == Process.myUid() &&
                actual.st_mode and 0x1ff == 0x180 && (!sealed || file.readText(Charsets.US_ASCII) == nonce)) {
                "Refusing to remove a replacement/non-owned retirement marker"
            }
            check(file.delete()) { "Could not release the exact owned retirement marker" }
            identity = null
        }
    }

    private fun processBirth(pid: Int): String = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]

    private fun processGone(pid: Int): Boolean = try { Os.stat("/proc/$pid"); false }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) true else throw error }

    private fun tunInterfaces(pid: Int): Set<String> {
        val entries = File("/proc/$pid/fd").listFiles() ?: error("Kernel FD evidence unavailable for primary VPN PID $pid")
        return entries.mapNotNull { tunInterface(pid, it.name) }.toSet()
    }

    private fun tunInterface(pid: Int, fd: String): String? = try {
        val target = Os.readlink("/proc/$pid/fd/$fd")
        if (target != "/dev/tun" && target != "/dev/net/tun") null
        else {
            File("/proc/$pid/fdinfo/$fd").readLines().firstOrNull { it.startsWith("iff:") }
                ?.substringAfter(':')?.trim() ?: error("Kernel TUN interface evidence unavailable for PID $pid FD $fd")
        }
    } catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw error }

    private fun runtimeNames(context: Context): Set<String> =
        (context.cacheDir.list() ?: error("Cannot inspect private runtime cleanup")).filter { name ->
            listOf("mcrf-primary-input-", "mcrf-primary-output-", "mcrf-run-", "mcrf-carrier-").any { name.startsWith(it) }
        }.toSet()

    private fun clean(context: Context, connectivity: ConnectivityManager, owned: List<OwnedRun>, before: Set<String>,
                      mainTunsBefore: Set<String>, requireDisconnected: Boolean = true): Boolean =
        owned.all { it.gone() } && workers().isEmpty() && primaryProcesses(context).isEmpty() &&
            !serviceExists(context) && (!requireDisconnected || McrfVpnService.status == DISCONNECTED) && McrfVpnService.localProxyPort == 0 &&
            connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } &&
            tunInterfaces(Process.myPid()) == mainTunsBefore && runtimeNames(context) == before

    private fun retiredProtectRejected(run: OwnedRun): Boolean = retiredProtectRejected(run.platform, run.worker.generation)

    private fun retiredProtectRejected(platform: Platform, generation: Long): Boolean = Socket().use { socket ->
        socket.bind(InetSocketAddress(0))
        ParcelFileDescriptor.fromSocket(socket).use { !platform.protectAndBind(generation, it.fd) }
    }

    private fun exchange(socket: Socket, peer: Origin, name: String,host:String=DESTINATION) {
        require(host==DESTINATION||host.matches(Regex("[0-9a-f]{32}\\.fixture\\.test")))
        socket.soTimeout = 8000
        val request = "POST ${peer.path}/$name HTTP/1.1\r\nHost: $host\r\nX-Mcrf-Fixture: ${peer.nonce}\r\nContent-Length: ${peer.upload.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); write(peer.upload); flush() }
        val header = readHeader(socket.getInputStream())
        check(header.lineSequence().first().trimEnd('\r') == "HTTP/1.1 200 OK" &&
            header.contains("\r\nContent-Length: ${peer.download.size}\r\n")) { "Owned origin returned an unexpected HTTP status/framing" }
        val data = readExactly(socket.getInputStream(), peer.download.size)
        check(data.contentEquals(peer.download) && socket.getInputStream().read() == -1) { "Actual HTTP payload differs from the owned origin's nonce-bearing response" }
    }

    private fun readHeader(input: InputStream): String {
        val output = ByteArrayOutputStream()
        while (output.size() < 8192) {
            val next = input.read(); check(next >= 0) { "Truncated local HTTP header" }; output.write(next)
            if (next == 10 && output.toString("US-ASCII").endsWith("\r\n\r\n")) return output.toString("US-ASCII")
        }
        error("Oversized local HTTP header")
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray = ByteArray(size).also { bytes ->
        var used = 0
        while (used < size) { val count = input.read(bytes, used, size - used); check(count > 0) { "Truncated local HTTP body" }; used += count }
    }

    private class Origin(private val address: Inet4Address, val nonce: String,private val allowRefusingProxy:Boolean=false,private val domainPlan:Boolean=false) : AutoCloseable {
        private val server = ServerSocket(0, 8, address).apply { soTimeout = 500 }
        private val executor = Executors.newSingleThreadExecutor()
        private val accepted = ConcurrentHashMap.newKeySet<String>()
        private val problem = AtomicReference<Throwable?>()
        val proxyRejects=java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var activeSocket: Socket? = null
        val port: Int = server.localPort
        val path = "/mcrf-primary-vpn-$nonce"
        val upload = ("upload-$nonce:" + "U".repeat(4096)).toByteArray(Charsets.US_ASCII)
        val download = ("download-$nonce:" + "D".repeat(8192)).toByteArray(Charsets.US_ASCII)
        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                        catch (error: Exception) { if (!server.isClosed) problem.compareAndSet(null, error); break }
                    activeSocket = socket
                    try { socket.use {
                        it.soTimeout = 8000
                        // A protected/bound module dial must return to the phone's physical IP.
                        check(it.localAddress == address && it.inetAddress == address) { "Origin received a non-owned/non-physical peer" }
                        val header = readHeader(it.getInputStream())
                        if(header.startsWith("CONNECT $DESTINATION:$DESTINATION_PORT HTTP/1.1\r\n")){
                            check(allowRefusingProxy){"Unexpected proxy hop outside the owned Direct fixture"}
                            proxyRejects.incrementAndGet()
                            it.getOutputStream().apply {write("HTTP/1.1 403 Owned proxy refusal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));flush()}
                            return@use
                        }
                        val names = listOf("protected", "tun-first", "tun-second", "tun-before-death", "tun-after-retirement", "tun-after-revoke-callback")
                        val name = names.singleOrNull { name -> header.startsWith("POST $path/$name HTTP/1.1\r\n") }
                            ?: error("Origin received a request outside this invocation")
                        val expectedHost=if(domainPlan&&name!="protected")OwnedDirectVpnPlan.hostname("fixture-owned-$nonce")else DESTINATION
                        check(header.contains("\r\nHost: $expectedHost\r\n")){"Owned HTTP request hostname differs from its domain/DNS plan"}
                        check(header.contains("\r\nX-Mcrf-Fixture: $nonce\r\n") &&
                            header.contains("\r\nContent-Length: ${upload.size}\r\n") &&
                            readExactly(it.getInputStream(), upload.size).contentEquals(upload)) { "Owned HTTP upload was not delivered intact" }
                        check(accepted.add(name)) { "Duplicate local fixture request $name" }
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${download.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            write(download); flush()
                        }
                    } } catch (error: Throwable) { if (!server.isClosed) problem.compareAndSet(null, error) }
                    finally { activeSocket = null }
                }
            }
        }
        fun assertReceived(name: String) {
            problem.get()?.let { throw IllegalStateException("Owned HTTP origin failed", it) }
            check(accepted.contains(name)) { "Owned origin never received $name" }
        }
        fun received(name:String):Boolean {
            problem.get()?.let {throw IllegalStateException("Owned HTTP origin failed",it)}
            return accepted.contains(name)
        }
        override fun close() {
            server.close(); activeSocket?.close(); executor.shutdownNow()
            check(executor.awaitTermination(10000, TimeUnit.MILLISECONDS)) { "Owned HTTP origin thread did not stop" }
            problem.get()?.let { throw IllegalStateException("Owned HTTP origin failed", it) }
        }
    }

    /** Compare decrypted stores AND raw protected files, not just a selected preference subset.
     * No values are logged and no restoration masks a service write. All snapshots live in RAM.
     */
    private data class Saved(val generation: String, val stores: Map<String, String>,
                             val preferences: Map<String, Map<String, Any?>>, val files: Map<String, String>) {
        companion object {
            fun capture(context: Context): Saved = synchronized(ProfileStore.storageLock) {
                val stores = ProfileStore.snapshot(context)
                val generation = ProfileStore.generation(context)
                val prefsRoot = File(context.applicationInfo.dataDir, "shared_prefs")
                val prefFiles = if (prefsRoot.exists()) prefsRoot.listFiles()?.toList() ?: error("Cannot snapshot full preferences") else emptyList()
                val names = setOf("MainActivity", "engine-updates", "identity", "telegram-service") +
                    prefFiles.filter { it.name.endsWith(".xml") || it.name.endsWith(".xml.bak") }
                        .map { it.name.removeSuffix(".bak").removeSuffix(".xml") }
                val preferences = names.associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
                    if (value is Set<*>) value.toSet() else value
                } }
                val protectedNames = setOf("profile.enc", "connections.enc", "routing.enc", "preferences.enc", "active-profile.txt")
                val raw = linkedMapOf<String, String>()
                fun add(file: File, key: String) {
                    check(!Files.isSymbolicLink(file.toPath()) && file.isFile) { "Cannot snapshot a protected symbolic/non-file path" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input -> val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                    raw[key] = "${file.length()}:${file.lastModified()}:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                }
                fun savedFile(name: String) = protectedNames.any { name == it || name == "$it.bak" || name == "$it.new" }
                val rootFiles = context.filesDir.listFiles() ?: error("Cannot snapshot saved profile files")
                rootFiles.filter { savedFile(it.name) }.forEach { add(it, "files/${it.name}") }
                rootFiles.filter { it.name.matches(Regex("mcrf-store-[0-9a-f-]{36}")) }.forEach { directory ->
                    check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
                    val entries = directory.listFiles() ?: error("Cannot snapshot saved store generation")
                    entries.filter { savedFile(it.name) }.forEach { add(it, "files/${directory.name}/${it.name}") }
                }
                prefFiles.forEach { add(it, "shared_prefs/${it.name}") }
                Saved(generation, stores, preferences, raw)
            }
        }
    }
}
