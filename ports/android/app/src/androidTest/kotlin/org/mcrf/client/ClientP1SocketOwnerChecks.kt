package org.mcrf.client

import android.app.Application
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.*
import android.os.*
import java.io.DataInputStream
import java.io.File
import java.net.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/** NEW common-P1 prerequisite, not a rerun of Direct tests. Borrow ONLY the
 * already-owned DEBUG fixture VPN. Real different-UID TCP/UDP loopback clients
 * are matched against independently observed peer tuples and installed UID.
 * This is platform original-owner evidence, NOT Steer JNI/app-routing proof.
 * Caller owns fresh protected-data baselines, sole device lease and helper pin. */
object ClientP1SocketOwnerChecks {
    private const val HELPER = "org.mcrf.p1owner"
    private class Session(val nonce: String) {
        val ready = CompletableFuture<Bundle>()
        val closed = CompletableFuture<Unit>()
        @Volatile var retiring = false
    }
    @JvmStatic fun run(context: Context, network: Network, expectedHelperSha256: String, passed: Consumer<String>) {
        check(BuildConfig.DEBUG && Looper.myLooper() != Looper.getMainLooper() &&
            Application.getProcessName() == context.packageName && context.packageName == "org.mcrf.client")
        val serviceField = McrfVpnService::class.java.getDeclaredField("currentService").apply { isAccessible = true }
        val service = requireNotNull(serviceField.get(null))
        val fixture = McrfVpnService::class.java.getDeclaredField("fixtureMode").apply { isAccessible = true }
        val ownerScope = McrfVpnService::class.java.getDeclaredField("fixtureP1OwnerHelperSha").apply { isAccessible = true }
        val epochField = McrfVpnService::class.java.getDeclaredField("epoch").apply { isAccessible = true }
        val epoch = epochField.getLong(service)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun live() {
            check(serviceField.get(null) === service && fixture.getBoolean(service) && ownerScope.get(service) == expectedHelperSha256 && epochField.getLong(service) == epoch) { "P1_OWNER_FIXTURE_RETIRED" }
            val caps = requireNotNull(cm.getNetworkCapabilities(network))
            check(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.ownerUid == android.os.Process.myUid()) { "P1_OWNER_OWNED_VPN_REQUIRED" }
        }
        live()
        require(expectedHelperSha256.matches(Regex("[0-9a-f]{64}")))
        val pm = context.packageManager
        fun inspect() = pm.getPackageInfo(HELPER, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SERVICES)
        val helper = inspect(); val app = requireNotNull(helper.applicationInfo)
        val owner = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        fun signers(info: android.content.pm.PackageInfo) = requireNotNull(info.signingInfo).apkContentsSigners.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }.sorted()
        val helperUid = app.uid
        check(helperUid >= 0 && helperUid != android.os.Process.myUid() && app.enabled &&
            (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && (app.flags and ApplicationInfo.FLAG_TEST_ONLY) != 0 &&
            signers(helper).isNotEmpty() && signers(helper) == signers(owner)) { "P1_OWNER_HELPER_IDENTITY" }
        val component = requireNotNull(helper.services).single { it.name == "$HELPER.OwnerProbeService" }
        check(component.exported && component.enabled && component.permission == "org.mcrf.client.permission.P1_OWNER")
        val source = File(app.sourceDir).canonicalFile
        fun hash() = source.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val originalHelperHash=hash()
        check(ClientP1OwnerFixtureScope.packages(context,expectedHelperSha256)==listOf(context.packageName,HELPER,"$HELPER.peer")){"P1_OWNER_HELPER_PIN"}
        val responses = HandlerThread("p1-owner-replies").apply { start() }
        val active = AtomicReference<Session?>()
        val connected = CompletableFuture<Messenger>()
        val reply = Messenger(Handler(responses.looper) { message ->
            val session = active.get(); val data = message.data
            if (session != null && message.sendingUid == helperUid && data.getString("nonce") == session.nonce) {
                when (message.what) {
                    3 -> if (!session.retiring) session.ready.complete(Bundle(data))
                    4 -> session.closed.complete(Unit) // IO unwound, never called process death
                    5 -> session.ready.completeExceptionally(IllegalStateException("P1_OWNER_HELPER_IO_FAILED"))
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName) {
                active.get()?.ready?.completeExceptionally(IllegalStateException("P1_OWNER_HELPER_DIED"))
                active.get()?.closed?.completeExceptionally(IllegalStateException("P1_OWNER_HELPER_DIED"))
            }
        }
        var bound = false
        var failure: Throwable? = null
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(HELPER, component.name)), connection, Context.BIND_AUTO_CREATE)
            check(bound); val remote = connected.get(5, TimeUnit.SECONDS)
            fun send(code: Int, data: Bundle) { remote.send(Message.obtain(null, code).apply { this.data = data; replyTo = reply }) }
            fun finish(session: Session) {
                session.retiring = true
                send(2, Bundle().apply { putString("nonce", session.nonce) })
                session.closed.get(4, TimeUnit.SECONDS); active.compareAndSet(session, null); live()
            }
            fun useSession(session: Session, work: () -> Unit) {
                var original: Throwable? = null
                try { work() } catch (error: Throwable) { original = error; throw error }
                finally {
                    if (active.get() === session) try { finish(session) }
                    catch (cleanup: Throwable) { if (original != null) original.addSuppressed(cleanup) else throw cleanup }
                }
            }
            fun start(protocol: Int, ip: InetAddress, port: Int): Session {
                live(); val session = Session(UUID.randomUUID().toString().replace("-", "")); check(active.compareAndSet(null, session))
                send(1, Bundle().apply { putString("nonce", session.nonce); putString("host", if (ip.address.size == 4) "127.0.0.1" else "::1"); putInt("port", port); putInt("protocol", protocol) })
                return session
            }
            fun checkOwner(session: Session, protocol: Int, local: InetSocketAddress, destination: InetSocketAddress) {
                val data = session.ready.get(4, TimeUnit.SECONDS); live()
                check(data.keySet() == setOf("nonce", "protocol", "source", "sourcePort", "destination", "destinationPort", "uid"))
                check(data.getInt("protocol") == protocol && data.getInt("uid") == helperUid &&
                    data.getByteArray("source")!!.contentEquals(local.address.address) && data.getInt("sourcePort") == local.port &&
                    data.getByteArray("destination")!!.contentEquals(destination.address.address) && data.getInt("destinationPort") == destination.port)
                val actual = cm.getConnectionOwnerUid(protocol, local, destination)
                check(actual == helperUid && actual != android.os.Process.myUid()) { "P1_ORIGINAL_CLIENT_UID_MISMATCH" }
                check(pm.getPackagesForUid(actual)?.toSet() == setOf(HELPER,"$HELPER.peer")) { "P1_ORIGINAL_CLIENT_PACKAGE_MISMATCH" }
                check(cm.getConnectionOwnerUid(protocol, destination, local) != helperUid) { "P1_LISTENER_TUPLE_WAS_NOT_REVERSED" }
            }
            for (family in listOf(4, 6)) {
                val ip = InetAddress.getByAddress(if (family == 4) byteArrayOf(127, 0, 0, 1) else ByteArray(16).also { it[15] = 1 })
                ServerSocket().use { listener ->
                    listener.bind(InetSocketAddress(ip, 0), 1); listener.soTimeout = 3500
                    val session = start(6, ip, listener.localPort)
                    useSession(session) {
                        listener.accept().use { peer ->
                            peer.soTimeout = 3500; val nonce = ByteArray(32); DataInputStream(peer.getInputStream()).readFully(nonce)
                            check(nonce.contentEquals(session.nonce.toByteArray(Charsets.US_ASCII))); peer.getOutputStream().write(nonce); peer.getOutputStream().flush()
                            checkOwner(session, 6, peer.remoteSocketAddress as InetSocketAddress, peer.localSocketAddress as InetSocketAddress)
                            finish(session); check(peer.getInputStream().read() == -1)
                        }
                    }
                    passed.accept("p1-original-helper-tcp$family-uid-package-tuple-io-close")
                }
                DatagramSocket(null).use { listener ->
                    listener.bind(InetSocketAddress(ip, 0)); listener.soTimeout = 3500
                    val session = start(17, ip, listener.localPort)
                    useSession(session) {
                        val packet = DatagramPacket(ByteArray(33), 33); listener.receive(packet)
                        check(packet.length == 32 && packet.data.copyOf(32).contentEquals(session.nonce.toByteArray(Charsets.US_ASCII)))
                        listener.send(DatagramPacket(packet.data, 32, packet.address, packet.port))
                        checkOwner(session, 17, InetSocketAddress(packet.address, packet.port), InetSocketAddress(ip, listener.localPort)); finish(session)
                    }
                    passed.accept("p1-original-helper-udp$family-uid-package-tuple-io-close")
                }
            }
            val after = inspect()
            check(after.longVersionCode == helper.longVersionCode && after.lastUpdateTime == helper.lastUpdateTime &&
                requireNotNull(after.applicationInfo).sourceDir == app.sourceDir && signers(after) == signers(helper) && hash() == originalHelperHash)
            check(ClientP1OwnerFixtureScope.packages(context,expectedHelperSha256)==listOf(context.packageName,HELPER,"$HELPER.peer"))
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            // No VPN/worker stop, force-stop, permission grant or profile rollback.
            val cleanup = mutableListOf<Throwable>()
            if (bound) try { context.unbindService(connection) } catch (error: Throwable) { cleanup += error }
            responses.quitSafely()
            try { responses.join(3000); check(!responses.isAlive) { "P1_OWNER_REPLY_THREAD_STILL_RUNNING" } }
            catch (error: Throwable) { cleanup += error }
            val originalFailure = failure
            if (originalFailure != null) cleanup.forEach { originalFailure.addSuppressed(it) }
            else if (cleanup.isNotEmpty()) { cleanup.drop(1).forEach { cleanup.first().addSuppressed(it) }; throw cleanup.first() }
        }
    }
}
