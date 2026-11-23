package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Owned Android fixture. The ARM variant requires a fresh, exact debug Main on an isolated device. */
class MixedCarrierSteerInstrumentation : Instrumentation() {
    private var args = Bundle()

    override fun onCreate(arguments: Bundle?) {
        args = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runFixture()
            if (args.getString("auto") == "true") {
                val scope = if (args.getString("expectSteerRefusal") == "true") {
                    "ordinary mixed automatic START with Steer refused before TUN or carrier; stored Steer preference preserved"
                } else "ordinary mixed START/STOP; authenticated sing-box rule before and after first QWDTT failure; marked autonomous QWDTT A to B switch and distinct TCP replies"
                result.putString("scope", scope)
                finish(Activity.RESULT_OK, result)
                return
            }
            val scope = if (args.getString("ordinary") == "true") "ordinary service START/STOP" else "independent workers"
            val protocolOwner = if (usesSingBoxRule()) "sing-box" else "Steer"
            val extended = if (args.getString("extended") == "true") ", owned TUN DNS and independent Steer retirement" else ""
            val udp = if (args.getString("udp") == "true") ", Steer UDP data echo" else ""
            result.putString("scope", "$scope, single Android TUN and sing-box dispatcher, physically bound signed carrier default, distinct signed $protocolOwner rule, TCP replies and cleanup$extended$udp")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("failure", error.stackTraceToString().take(3000))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun runFixture() {
        val target = targetContext
        val armHost = args.getString("ownedArmHost")
        val installed = target.packageManager.getApplicationInfo(target.packageName, 0)
        check(installed.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        if (armHost == null) {
            check(BuildConfig.DEBUG && Build.HARDWARE in setOf("ranchu", "goldfish"))
        } else {
            check(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && args.getString("ordinary") == "true")
            check(ClientPrimaryWorkerWire.hashFile(File(installed.sourceDir)) == args.getString("ownedMainSha256"))
            check(ProfileStore.snapshot(target).values.all(String::isBlank))
            check(target.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).all.isEmpty())
        }
        check(!McrfVpnService.hasActiveSession())
        if (!usesSingBoxRule()) check(VpnService.prepare(target) == null)
        val physical = checkNotNull(DirectAccessCheck.physical(target))
        if (args.getString("auto") == "true") {
            automatic(target)
            return
        }
        val gateway = armHost ?: "10.0.2.2"
        val gatewayAddress = RouteRules.literal(gateway)
        require(gatewayAddress is Inet4Address && gatewayAddress.isSiteLocalAddress)
        val carrierAddress = RouteRules.literal(requireNotNull(args.getString("carrierEchoAddress")))
        require(carrierAddress is Inet4Address && carrierAddress.isSiteLocalAddress)
        require(armHost != null || carrierAddress.hostAddress != gateway)
        val carrierPort = port("carrierEchoPort")
        val ruleAddress = "192.0.2.42"
        val rulePort = 23456
        val ordinary = args.getString("ordinary") == "true"
        val steerPin = if (!usesSingBoxRule()) NativeRouteWorkers.modulePin(target).also {
            check(it == args.getString("steerSha256"))
        } else null
        val dispatcher = EngineUpdatePrimary.installation(target, "sing-box")
        check(ClientPrimaryWorkerWire.hashFile(File(dispatcher.sourceDir)) == args.getString("singboxSha256"))
        val carrier = NativeCarrier.installation(target, "openflux")
        check(ClientPrimaryWorkerWire.hashFile(File(carrier.sourceDir)) == args.getString("carrierSha256"))
        if (usesSingBoxRule()) VpnService.prepare(target)?.let { consent ->
            val saved = ProfileStore.snapshot(target)
            val prefs = target.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
            val savedPreferences = prefs.all.toMap()
            val activity = startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                runOnMainSync { activity.startActivityForResult(consent, 8831) }
                val deadline = SystemClock.elapsedRealtime() + 90000
                while (VpnService.prepare(target) != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(250)
                check(VpnService.prepare(target) == null) { "Android VPN consent was not granted for the isolated fixture" }
            } finally {
                runOnMainSync { activity.finish() }
                waitForIdleSync()
                ProfileStore.replaceAll(target, saved)
                restorePreferences(prefs, savedPreferences)
            }
        }

        val profile = JSONObject().put("type", "openflux").put("negotiate", true)
            .put("secret", "owned fixture encryption").put("context", "owned named egress fixture")
            .put("transports", JSONArray().put(JSONObject().put("type", "direct").put("dial", "$gateway:${port("carrierExitPort")}")))
        val carrierEntry = ServerImport.Server("owned-mixed-carrier", "owned mixed carrier", "openflux", "", 0,
            null, null, null, profile)
        val steerScheme = if (args.getString("udp") == "true") "socks" else "http"
        val steerEntry = ServerImport.read("$steerScheme://owned-user:owned-password@$gateway:${port("steerExitPort")}#owned-steer").single()
        if (armHost != null) exchange(gateway, steerEntry.port, 'P', 'L', physical)
        val rule = JSONObject().put("id", "steer").put("name", "steer").put("target", "proxy")
            .put("mode", "addresses").put("text", "$ruleAddress/32").put("serverId", steerEntry.id)
        val routing = RouteRules.defaults().put("rules", JSONArray().put(rule))
        val source = ServerImport.build(listOf(carrierEntry), carrierEntry.id, false, 60, 50, "Cloudflare")
        if (ordinary) {
            ordinary(target, source, routing, steerEntry, carrierAddress.hostAddress!!, carrierPort, ruleAddress, rulePort)
            return
        }
        val mainTag = source.getJSONObject("mcrf_carrier").getString("outbound_tag")
        source.remove("mcrf_carrier")
        source.put("inbounds", JSONArray().put(JSONObject().put("type", "tun").put("tag", "tun")
            .put("address", JSONArray().put("10.89.0.1/30")).put("mtu", 1500).put("auto_route", true)
            .put("route_address", JSONArray(listOf("${carrierAddress.hostAddress}/32", "$ruleAddress/32", "$gateway/32")))
            .put("include_package", JSONArray().put(target.packageName))))
        val compiled = RouteRules.apply(source, routing, true, routeServers = listOf(steerEntry))
        check(compiled.getJSONObject("route").getString("final") == mainTag)
        val extra = RouteServerPlan(routing, listOf(steerEntry))
        val plan = routeOnlyPlan(extra, steerEntry)
        check(plan.bindings.single().tags == listOf(RouteServerPlan.tag(steerEntry.id)))

        val deadline = SystemClock.elapsedRealtime() + 35000
        val stopped = BooleanSupplier { SystemClock.elapsedRealtime() >= deadline }
        val host = object : ClientPrimaryHostPlatform() {
            fun attach() { attachBaseContext(target); vpnMode = true }
        }.apply { attach(); onCreate() }
        val facade = McrfVpnService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }
            .invoke(facade, target)
        var tun: ParcelFileDescriptor? = null
        val executor = Executors.newFixedThreadPool(2)
        try {
            val platform = host.forRun(45, { stopped.asBoolean }, { plan ->
                check(tun == null)
                val builder = facade.Builder().setSession("MCRF owned mixed exits").setMtu(plan.mtu)
                (plan.routes.inet4Address + plan.routes.inet6Address).forEach { value ->
                    val (address, prefix) = value.split('/')
                    builder.addAddress(address, prefix.toInt())
                }
                (plan.routes.inet4Range + plan.routes.inet6Range).forEach { value ->
                    val (address, prefix) = value.split('/')
                    builder.addRoute(address, prefix.toInt())
                }
                plan.dnsServers.forEach(builder::addDnsServer)
                plan.includePackage.forEach(builder::addAllowedApplication)
                plan.excludePackage.forEach(builder::addDisallowedApplication)
                checkNotNull(builder.establish()).also { tun = it }
            }) { _, _ -> }
            startCarrier(target, profile, physical, stopped).use { owner ->
                val outbounds = compiled.getJSONArray("outbounds")
                val node = (0 until outbounds.length()).map(outbounds::getJSONObject).single { it.getString("tag") == mainTag }
                node.put("server_port", owner.port).put("username", owner.username).put("password", owner.password)
                NativeRoutePlatforms(platform, 45, { stopped.asBoolean }).use { children ->
                    NativeRouteWorkers.start(target, compiled, plan, 45, checkNotNull(steerPin), stopped, children::child, { gateway }).use { routes ->
                        ClientPrimaryWorker.connect(target, "singbox", 45, platform).use { worker ->
                            val settings = ClientPrimarySelection.envelope(routes.config, true, false)
                            val mode = org.mcrf.engine.spi.v1.Engine.Mode.VPN
                            check(worker.validate("", settings, mode).supported)
                            check(tun == null)
                            worker.start("", settings, mode)
                            check(tun != null && worker.state == "RUNNING")
                            val carrierFlow = executor.submit { exchange(carrierAddress.hostAddress!!, carrierPort, 'A', 'A') }
                            val steerFlow = executor.submit { exchange(ruleAddress, rulePort, 'B', 'S') }
                            carrierFlow.get(9, TimeUnit.SECONDS)
                            steerFlow.get(9, TimeUnit.SECONDS)
                            if (args.getString("udp") == "true") verifyUdp(target, ruleAddress, rulePort)
                            routes.requireListening()
                            owner.checkFailure()
                        }
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            host.onDestroy()
            tun?.close()
        }
    }

    private fun automatic(target: Context) {
        val saved = ProfileStore.snapshot(target)
        val prefs = target.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val savedPreferences = prefs.all.toMap()
        val manager = target.getSystemService(ConnectivityManager::class.java)
        fun vpns() = manager.allNetworks.filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        fun field(name: String) = McrfVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }
        val service = field("currentService")
        val selected = field("selectedId")
        fun selectedId() = service.get(null)?.let(selected::get) as? String
        fun waitFor(timeout: Long, ready: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            check(ready()) { McrfVpnService.status }
        }
        check(vpns().isEmpty())
        val firstLink = requireNotNull(args.getString("firstOwnedCandidate"))
        val secondLink = requireNotNull(args.getString("secondOwnedCandidate"))
        val first = ServerImport.read(firstLink).single()
        val second = ServerImport.read(secondLink).single()
        check(first.id != second.id)
        val peerPorts = listOf(38740, 38840)
        listOf(first to peerPorts[0], second to peerPorts[1]).forEach { (entry, peerPort) ->
            check(entry.protocol == "qwdtt" && entry.original.contains("127.0.0.1%3A$peerPort"))
            check(BsNativeContract.routerTransport(target, checkNotNull(entry.carrier), true))
        }
        val healthAddress = "10.200.0.2"
        val ruleAddress = "192.0.2.42"
        val rulePeer = ServerImport.read("http://owned-user:owned-password@10.0.2.2:${port("steerExitPort")}#owned-singbox-rule").single()
        val source = BsPlan.build(listOf(first, second), 5, 0, "Cloudflare")
        val expectSteerRefusal = args.getString("expectSteerRefusal") == "true"
        val primary = if (expectSteerRefusal) "steer" else "singbox"
        val hosts = JSONObject().put("www.gstatic.com", healthAddress)
        source.put("dns", JSONObject().put("final", "owned").put("servers", JSONArray().put(
            JSONObject().put("type", "hosts").put("tag", "owned").put("predefined", hosts))))
        source.getJSONObject("route").put("default_domain_resolver", "owned")
        val outputs = source.getJSONArray("outbounds")
        repeat(outputs.length()) { outputs.getJSONObject(it).remove("domain_resolver") }
        try {
            check(prefs.edit().clear().commit())
            val catalogue = ConnectionCatalogue("")
            listOf(first, second, rulePeer).forEach { check(catalogue.add(it.original) == 1) }
            catalogue.setAuto(first.id, true)
            catalogue.setAuto(second.id, true)
            ProfileStore(target, "connections.enc").write(catalogue.serialize())
            ExperienceModeStore(target).save(ExperienceMode.EXPERT)
            RoutingStore(target).apply {
                load()
                val rule = JSONObject().put("id", "owned-protocol").put("name", "owned-protocol").put("mode", "addresses")
                    .put("text", "$ruleAddress/32").put("target", "proxy").put("serverId", rulePeer.id)
                save(snapshot().put("rules", JSONArray().put(rule)))
            }
            ProfileStore(target).write(source.toString())
            check(prefs.edit().putString(ClientPrimarySelection.KEY, primary).putBoolean("automatic", true)
                .putString("autoPolicy", AutoSelection.BS_ENGINES).putInt("interval", 5)
                .putBoolean("catalogueProfile", false).putBoolean(BsDetectionMonitor.KEY, false).commit())
            val proxyPort = java.net.ServerSocket(0).use { it.localPort }
            target.startForegroundService(Intent(target, McrfVpnService::class.java).setAction(McrfVpnService.START)
                .putExtra("tun", true).putExtra("applyRoutes", true).putExtra("preparedProxyPort", proxyPort)
                .putExtra("pendingWhitelists", true))
            if (expectSteerRefusal) {
                val deadline = SystemClock.elapsedRealtime() + 10000
                do {
                    check(vpns().isEmpty() && selectedId().isNullOrEmpty()) { "Unsupported mixed automatic Steer opened a VPN or selected a server" }
                    val detail = LastConnectionFailure.detail()
                    if (detail?.contains("Смешанные правила Steer требуют ручного подключения в режиме VPN.") == true) break
                    check(SystemClock.elapsedRealtime() < deadline) { McrfVpnService.status }
                    Thread.sleep(25)
                } while (true)
                check(ClientPrimarySelection.selected(target) == primary)
                check(JSONObject(ProfileStore(target).read()).toString() == source.toString())
                return
            }
            waitFor(35000) { McrfVpnService.status.startsWith("VPN подключён") && vpns().size == 1 && selectedId() == first.id }
            exchange(ruleAddress, 23456, 'B', 'S')
            exchange(healthAddress, 38741, 'A', '1')
            // The first peer retires only after acknowledging the owned 'F' control byte.
            exchange(healthAddress, 38741, 'F', '1')
            waitFor(55000) { McrfVpnService.status.startsWith("VPN подключён") && vpns().size == 1 && selectedId() == second.id }
            exchange(healthAddress, 38741, 'A', '2')
            exchange(ruleAddress, 23456, 'B', 'S')
            check(ClientPrimarySelection.selected(target) == "singbox")
        } finally {
            try {
                target.startService(Intent(target, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                waitFor(10000) { !McrfVpnService.hasActiveSession() && !McrfVpnService.cleanupPending() && vpns().isEmpty() }
            } finally {
                ProfileStore.replaceAll(target, saved)
                restorePreferences(prefs, savedPreferences)
            }
        }
    }

    private fun ordinary(target: Context, source: JSONObject, routing: JSONObject, steer: ServerImport.Server,
                         carrierHost: String, carrierPort: Int, ruleHost: String, rulePort: Int) {
        val saved = ProfileStore.snapshot(target)
        val prefs = target.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val savedPreferences = prefs.all.toMap()
        val manager = target.getSystemService(ConnectivityManager::class.java)
        fun vpnCount() = manager.allNetworks.count { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        fun waitFor(timeout: Long, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            check(condition()) { "${McrfVpnService.status}: ${LastConnectionFailure.detail().orEmpty()}" }
        }
        check(vpnCount() == 0)
        val executor = Executors.newFixedThreadPool(2)
        var routeTrace: File? = null
        try {
            check(prefs.edit().clear().commit())
            val arm = args.getString("ownedArmHost") != null
            val uidScope = arm || args.getString("ownedUidScope") == "true"
            val primary = if (usesSingBoxRule()) "singbox" else "steer"
            if (arm && args.getString("ownedRouteTrace") == "true") {
                routeTrace = File.createTempFile("mixed-route-", ".log", target.cacheDir)
                source.put("log", JSONObject().put("level", "debug").put("output", routeTrace.absolutePath))
            }
            val catalogue = ConnectionCatalogue("")
            check(catalogue.add(steer.original) == 1)
            ProfileStore(target, "connections.enc").write(catalogue.serialize())
            ExperienceModeStore(target).save(ExperienceMode.EXPERT)
            val rules = routing.getJSONArray("rules")
            rules.put(JSONObject().put("id", "default").put("name", "default").put("mode", "addresses")
                .put("text", "$carrierHost/32").put("target", "proxy"))
            rules.put(JSONObject().put("id", "disabled").put("name", "disabled").put("mode", "addresses")
                .put("text", "192.0.2.43/32").put("target", "proxy").put("serverId", "c".repeat(64)).put("enabled", false))
            RoutingStore(target).apply {
                load()
                val candidate = snapshot().put("rules", rules)
                if (uidScope) candidate.put("appMode", "include").put("vpnApps", JSONArray().put(target.packageName))
                save(candidate)
            }
            val extended = args.getString("extended") == "true"
            if (extended) {
                val hosts = JSONObject().put("owned-mixed.invalid", ruleHost)
                val healthAddress = RouteRules.literal(requireNotNull(args.getString("healthAddress")))
                require(healthAddress is Inet4Address && !healthAddress.isLoopbackAddress && !healthAddress.isSiteLocalAddress)
                hosts.put("www.gstatic.com", healthAddress.hostAddress)
                source.put("dns", JSONObject().put("final", "owned").put("servers", JSONArray().put(
                    JSONObject().put("type", "hosts").put("tag", "owned").put("predefined", hosts))))
                source.getJSONObject("route").put("default_domain_resolver", "owned")
                val nodes = source.getJSONArray("outbounds")
                repeat(nodes.length()) { nodes.getJSONObject(it).remove("domain_resolver") }
            }
            ProfileStore(target).write(source.toString())
            check(prefs.edit().putString(ClientPrimarySelection.KEY, primary).putBoolean("automatic", false)
                .putBoolean("catalogueProfile", false).putBoolean(BsDetectionMonitor.KEY, false).commit())
            val proxyPort = java.net.ServerSocket(0).use { it.localPort }
            val healthService = McrfVpnService::class.java.getDeclaredField("currentService").apply { isAccessible = true }
            val healthCore = McrfVpnService::class.java.getDeclaredField("core").apply { isAccessible = true }
            val healthUrl = McrfVpnService::class.java.getDeclaredField("healthUrl").apply { isAccessible = true }
            target.startForegroundService(Intent(target, McrfVpnService::class.java).setAction(McrfVpnService.START)
                .putExtra("tun", true).putExtra("applyRoutes", true).putExtra("preparedProxyPort", proxyPort)
                .putExtra("pendingWhitelists", true))
            if (args.getString("ownedHealthPort") != null) {
                val url = "http://$carrierHost:${port("ownedHealthPort")}/owned-health"
                waitFor(15000) {
                    val service = healthService.get(null)
                    if (service == null || healthCore.get(service) == null) false else {
                        healthUrl.set(service, url)
                        true
                    }
                }
            }
            waitFor(25000) { McrfVpnService.status.startsWith("VPN подключён") && vpnCount() == 1 }
            val scopedVpn = if (uidScope) manager.allNetworks.single { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } else null
            if (uidScope) verifyArmRoute(steer, ruleHost)
            val carrierFlow = executor.submit {
                if (arm) {
                    val payload = ByteArray(8192) { (it % 251).toByte() }
                    Socket().use { socket ->
                        checkNotNull(scopedVpn).bindSocket(socket)
                        socket.connect(InetSocketAddress(carrierHost, carrierPort), 6000)
                        socket.soTimeout = 6000
                        socket.getOutputStream().write(payload)
                        val echo = ByteArray(payload.size)
                        java.io.DataInputStream(socket.getInputStream()).readFully(echo)
                        check(echo.contentEquals(payload))
                    }
                } else exchange(carrierHost, carrierPort, 'A', 'A', scopedVpn)
            }
            val steerFlow = executor.submit {
                if (arm) exchange(ruleHost, rulePort, 'B', 'S', scopedVpn, 8192)
                else exchange(ruleHost, rulePort, 'B', 'S', scopedVpn)
            }
            carrierFlow.get(9, TimeUnit.SECONDS)
            steerFlow.get(9, TimeUnit.SECONDS)
            if (extended) {
                val vpn = manager.allNetworks.single { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
                val dns = checkNotNull(manager.getLinkProperties(vpn)?.dnsServers?.firstOrNull())
                val query = PacketDnsWire.query("owned-mixed.invalid", 1, 4551)
                DatagramSocket(null).use { socket ->
                    vpn.bindSocket(socket)
                    socket.bind(InetSocketAddress(0))
                    socket.soTimeout = 5000
                    socket.send(DatagramPacket(query, query.size, dns, 53))
                    val reply = DatagramPacket(ByteArray(2048), 2048)
                    socket.receive(reply)
                    check(PacketDnsWire.addresses(query, reply.data.copyOf(reply.length)) == listOf(RouteRules.literal(ruleHost)))
                }
                if (args.getString("udp") == "true") {
                    verifyUdp(target, ruleHost, rulePort)
                }
                val service = McrfVpnService::class.java.getDeclaredField("currentService").apply { isAccessible = true }.get(null)
                val owner = McrfVpnService::class.java.getDeclaredField("nativeRouteSession").apply { isAccessible = true }
                    .get(service) as NativeRouteWorkers
                owner.close()
                check(runCatching { exchange(ruleHost, rulePort, 'B', 'S') }.isFailure) { "Retired Steer rule unexpectedly served traffic" }
                exchange(carrierHost, carrierPort, 'A', 'A')
                check(manager.allNetworks.contains(vpn)) { "Retiring Steer also retired the TUN" }
            }
            check(vpnCount() == 1 && ClientPrimarySelection.selected(target) == primary)
            check(JSONObject(ProfileStore(target).read()).toString() == source.toString())
        } finally {
            executor.shutdownNow()
            try {
                target.startService(Intent(target, McrfVpnService::class.java).setAction(McrfVpnService.STOP))
                waitFor(10000) { !McrfVpnService.hasActiveSession() && !McrfVpnService.cleanupPending() }
            } finally {
                routeTrace?.let { trace ->
                    val tag = RouteServerPlan.tag(steer.id)
                    trace.useLines { lines -> lines.filter { ruleHost in it || tag in it || "outbound/http" in it }
                        .take(30).forEach { android.util.Log.i("McrfMixedOwned", ClientEvents.redact(it).take(600)) } }
                    check(trace.delete())
                }
                ProfileStore.replaceAll(target, saved)
                restorePreferences(prefs, savedPreferences)
            }
        }
    }

    private fun restorePreferences(prefs: android.content.SharedPreferences, saved: Map<String, *>) {
        val restore = prefs.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is String -> restore.putString(key, value)
            is Boolean -> restore.putBoolean(key, value)
            is Int -> restore.putInt(key, value)
            is Long -> restore.putLong(key, value)
            is Float -> restore.putFloat(key, value)
            is Set<*> -> restore.putStringSet(key, value.map { it as String }.toSet())
            else -> error("Unexpected preference type")
        } }
        check(restore.commit() && prefs.all == saved)
    }

    private fun verifyUdp(target: Context, host: String, port: Int) {
        val manager = target.getSystemService(ConnectivityManager::class.java)
        val vpn = manager.allNetworks.single { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        DatagramSocket(null).use { socket ->
            vpn.bindSocket(socket)
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 7000
            val data = byteArrayOf(0, 5, 9, 127, -1)
            val destination = checkNotNull(RouteRules.literal(host))
            socket.send(DatagramPacket(data, data.size, destination, port))
            val reply = DatagramPacket(ByteArray(64), 64)
            socket.receive(reply)
            check(reply.address == destination && reply.port == port)
            check(reply.data.copyOf(reply.length).contentEquals(data.reversedArray()))
        }
    }

    // The frozen target predates these two source APIs. This adapter uses the
    // real worker/dispatcher classes and keeps the compatibility code in tests.
    private fun routeOnlyPlan(extra: RouteServerPlan, entry: ServerImport.Server): NativeRouteDispatchPlan {
        val existing = NativeRouteDispatchPlan.forSteer(entry, extra)
        val routeTag = RouteServerPlan.tag(entry.id)
        val original = existing.bindings.single()
        val binding = NativeRouteDispatchPlan.Binding(original.id, original.host, original.secretProfile(),
            listOf(routeTag), checkNotNull(entry.outbound))
        return NativeRouteDispatchPlan::class.java.getDeclaredConstructor(List::class.java).apply { isAccessible = true }
            .newInstance(listOf(binding))
    }

    private fun startCarrier(context: Context, profile: JSONObject, physical: android.net.Network,
                             stopped: BooleanSupplier): NativeCarrier {
        val companion = NativeCarrier.Companion
        val method = companion.javaClass.methods.single {
            it.name.startsWith("startForBsRouter$") && !it.name.endsWith("\$default")
        }
        val values = arrayOf<Any?>(context, profile, physical, stopped, 12000L)
        return method.invoke(companion, *(if (method.parameterCount == 5) values else values + null)) as NativeCarrier
    }

    private fun port(key: String): Int = requireNotNull(args.getString(key)).toInt().also { require(it in 1024..65535) }

    private fun usesSingBoxRule() = args.getString("ownedArmHost") != null || args.getString("singBoxRule") == "true"

    private fun verifyArmRoute(entry: ServerImport.Server, destination: String) {
        val serviceClass = McrfVpnService::class.java
        val service = serviceClass.getDeclaredField("currentService").apply { isAccessible = true }.get(null)
        val config = serviceClass.getDeclaredField("runningConfig").apply { isAccessible = true }.get(service) as JSONObject
        val tag = RouteServerPlan.tag(entry.id)
        val route = config.getJSONObject("route")
        check(route.getString("final") != tag)
        val rules = route.getJSONArray("rules")
        val matching = (0 until rules.length()).map(rules::getJSONObject).filter { it.optString("outbound") == tag }
        check(matching.any { it.optJSONArray("ip_cidr")?.optString(0) == "$destination/32" })
        val outbounds = config.getJSONArray("outbounds")
        val node = (0 until outbounds.length()).map(outbounds::getJSONObject).single { it.optString("tag") == tag }
        check(node.optString("type") == "http" && node.optString("server") == entry.host && node.optInt("server_port") == entry.port)
        android.util.Log.i("McrfMixedOwned", "Captured CIDR selects HTTP; literal peer and port match; independent final outbound")
    }

    private fun exchange(host: String, port: Int, request: Char, reply: Char, network: android.net.Network? = null,
                         payloadSize: Int = 1) {
        Socket().use { socket ->
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(host, port), 6000)
            socket.soTimeout = 6000
            socket.getOutputStream().write(ByteArray(payloadSize) { request.code.toByte() })
            check(socket.getInputStream().read() == reply.code)
        }
    }
}
