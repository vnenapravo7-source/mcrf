package org.mcrf.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.RouteInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.zip.ZipFile

/** QUIC handshake and exchange through a live TUN, against a pinned owned origin. */
internal class ClientQuicOwnerChecks private constructor(
    private val context: Context,
    private val helper: String,
    private val helperUid: Int,
    private val executable: File,
    private val server: FixtureProcess?,
    private val certificate: String,
    private val certificateSha256: String,
    val port: Int
) : AutoCloseable {
    private data class Snapshot(val accepted: Int, val datagrams: Long)
    private var stopped = false
    private val wireGuard get() = server == null

    fun run(network: Network, assertCurrent: () -> Unit, passed: Consumer<String>) {
        check(!wireGuard)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val links = requireNotNull(cm.getLinkProperties(network))
        val target = InetAddress.getByName(ClientP1StrictOwnerFixture.IPV4)
        check(network.networkHandle != 0L && links.linkAddresses.any { it.address.hostAddress == "172.19.0.1" } &&
            links.routes.any { it.type == RouteInfo.RTN_UNICAST && it.destination.contains(target) }) {
            "QUIC requires the owned VPN Network and synthetic TUN route"
        }
        assertCurrent()
        val initial = snapshot()
        check(initial == Snapshot(0, 0)) { "QUIC origin already received traffic" }
        positive(network)
        val beforeDenial = settledSnapshot()
        check(beforeDenial.accepted == 1 && beforeDenial.datagrams > 0)
        passed.accept("p1-strict-quic-main-before QUICv1 TLS=1.3 ALPN=$ALPN")

        assertCurrent()
        excluded(network)
        val afterDenial = snapshot()
        check(afterDenial == beforeDenial) { "Excluded UID traffic reached the owned QUIC origin" }
        passed.accept("p1-strict-quic-excluded-bind-denied serverDatagramsUnchanged=true")

        assertCurrent()
        positive(network)
        val afterRetry = snapshot()
        check(afterRetry.accepted == 2 && afterRetry.datagrams > afterDenial.datagrams)
        passed.accept("p1-strict-quic-main-after QUICv1 TLS=1.3 ALPN=$ALPN")
        assertCurrent()
        val final = stopServer()
        check(final.accepted == 2 && final.datagrams >= afterRetry.datagrams)
        passed.accept("p1-strict-quic-origin-stopped-eof")
    }

    fun runWireGuard(network: Network, assertCurrent: () -> Unit, passed: Consumer<String>): String {
        check(wireGuard)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val links = requireNotNull(cm.getLinkProperties(network))
        val target = InetAddress.getByName("10.77.0.1")
        check(network.networkHandle != 0L && links.linkAddresses.any { it.address.hostAddress == "10.77.0.2" } &&
            links.routes.any { it.type == RouteInfo.RTN_UNICAST && it.destination.contains(target) }) {
            "WG QUIC requires the owned VPN Network and fixture route"
        }
        assertCurrent()
        val before = positive(network)
        passed.accept("wg-strict-quic-main-before QUICv1 TLS=1.3 ALPN=$ALPN")
        assertCurrent()
        excluded(network)
        passed.accept("wg-strict-quic-excluded-vpn-bind-denied")
        assertCurrent()
        val after = positive(network)
        check(before != after)
        assertCurrent()
        passed.accept("wg-strict-quic-main-after QUICv1 TLS=1.3 ALPN=$ALPN")
        return sha256("mcrf-wg-quic-v1\n$before\n$after\n".byteInputStream(Charsets.US_ASCII))
    }

    private fun positive(network: Network): String {
        val nonce = nonce()
        val handle = java.lang.Long.toUnsignedString(network.networkHandle)
        val laneArgs = if (wireGuard) arrayOf("--lane", "wg") else emptyArray()
        FixtureProcess(executable, "client", "--network", handle, "--certificate", certificate,
            "--certificate-sha256", certificateSha256, "--nonce", nonce, *laneArgs).use { client ->
            client.event("client_result").also { result ->
                check(result.opt("status") == "QUIC_COMPLETE") { "Main QUIC client did not complete" }
                requireExchange(result, nonce)
            }
            client.exitAndEof()
        }
        server?.let { requireExchange(it.event("accepted"), nonce) }
        return nonce
    }

    private fun excluded(network: Network) {
        val connected = CompletableFuture<Messenger>()
        val result = CompletableFuture<Bundle>()
        val retired = CompletableFuture<Unit>()
        val nonce = nonce()
        val thread = HandlerThread("owned-quic-helper-reply").apply { start() }
        val reply = Messenger(Handler(thread.looper) { message ->
            if (message.sendingUid == helperUid && message.data.getString("nonce") == nonce) {
                when (message.what) {
                    HELPER_QUIC -> result.complete(Bundle(message.data))
                    HELPER_RETIRED -> retired.complete(Unit)
                }
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(Messenger(service))
            }

            override fun onServiceDisconnected(name: ComponentName) = lost()
            override fun onBindingDied(name: ComponentName) = lost()
            override fun onNullBinding(name: ComponentName) = lost()

            private fun lost() {
                val error = IllegalStateException("Owned QUIC helper binding lost")
                connected.completeExceptionally(error)
                result.completeExceptionally(error)
                retired.completeExceptionally(error)
            }
        }
        var bound = false
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(helper, "$helper.OwnerProbeService")),
                connection, Context.BIND_AUTO_CREATE)
            check(bound)
            val remote = connected.get(5, TimeUnit.SECONDS)
            remote.send(Message.obtain(null, HELPER_QUIC).apply {
                replyTo = reply
                data = Bundle().apply {
                    putString("nonce", nonce)
                    putLong("network", network.networkHandle)
                    putString("certificate", certificate)
                    putString("certificateSha256", certificateSha256)
                    if (wireGuard) putString("lane", "wg")
                }
            })
            val outcome = result.get(8, TimeUnit.SECONDS)
            retired.get(5, TimeUnit.SECONDS)
            check(outcome.keySet() == setOf("nonce", "uid", "code") && outcome.getInt("uid") == helperUid &&
                outcome.getString("code") == "QUIC_BIND_DENIED") { "No explicit VPN-bind denial from the excluded QUIC helper" }
        } finally {
            try {
                if (bound) context.unbindService(connection)
            } finally {
                thread.quitSafely()
                thread.join(3000L)
                check(!thread.isAlive) { "Owned QUIC reply worker did not retire" }
            }
        }
    }

    private fun snapshot(): Snapshot {
        val local = checkNotNull(server)
        local.command("SNAPSHOT")
        return counts(local.event("snapshot"))
    }

    private fun settledSnapshot(): Snapshot {
        var previous = snapshot()
        var unchanged = 0
        repeat(8) {
            SystemClock.sleep(150L)
            val next = snapshot()
            unchanged = if (next == previous) unchanged + 1 else 0
            if (unchanged == 3) return next
            previous = next
        }
        error("Owned QUIC origin did not become idle")
    }

    private fun stopServer(): Snapshot {
        val local = checkNotNull(server)
        local.command("STOP")
        val counts = counts(local.event("stopped"))
        local.exitAndEof()
        stopped = true
        return counts
    }

    override fun close() {
        val local = server ?: return
        try {
            if (!stopped && local.isAlive) stopServer()
        } finally {
            local.close()
        }
    }

    private class FixtureProcess(path: File, vararg args: String) : AutoCloseable {
        private val process = ProcessBuilder(listOf(path.path) + args)
            .redirectError(File("/dev/null")).start()
        private val stdout = process.inputStream.buffered()
        private val reads = Executors.newSingleThreadExecutor { task ->
            Thread(task, "owned-quic-process-output").apply { isDaemon = true }
        }
        val isAlive: Boolean get() = process.isAlive

        fun command(command: String) {
            val input = process.outputStream
            input.write((command + "\n").toByteArray(Charsets.US_ASCII))
            input.flush()
        }

        fun event(expected: String): JSONObject {
            val raw = reads.submit(Callable { boundedLine(stdout) }).get(8, TimeUnit.SECONDS)
            val body = try { JSONObject(raw) } catch (_: JSONException) {
                error("Malformed owned QUIC process event ($expected)")
            }
            check(body.optString("event") == expected) { "Unexpected owned QUIC process event ($expected)" }
            return body
        }

        fun exitAndEof() {
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "Owned QUIC process did not exit successfully" }
            check(reads.submit(Callable { stdout.read() }).get(2, TimeUnit.SECONDS) == -1) { "Owned QUIC process left unexpected output" }
        }

        override fun close() {
            try {
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        check(process.waitFor(2, TimeUnit.SECONDS)) { "Owned QUIC process still running" }
                    }
                }
            } finally {
                try {
                    if (process.isAlive) process.destroyForcibly()
                    process.outputStream.close()
                } finally {
                    try {
                        stdout.close()
                    } finally {
                        reads.shutdownNow()
                        check(reads.awaitTermination(3, TimeUnit.SECONDS)) { "Owned QUIC output reader still running" }
                    }
                }
            }
        }
    }

    companion object {
        private const val ALPN = "mcrf-owner-quic-v1"
        private const val HELPER_QUIC = 9
        private const val HELPER_RETIRED = 4
        private const val LIBRARY = "libmcrf_owned_quic_fixture.so"
        private data class VerifiedClient(val helper: String, val uid: Int, val executable: File)

        private fun verifyClient(context: Context, pin: String): VerifiedClient {
            val helper = ClientP1OwnerFixtureScope.packages(context, pin)[1]
            val packageInfo = context.packageManager.getPackageInfo(helper, 0)
            check(packageInfo.longVersionCode == 2L) { "Owned QUIC helper version 2 required" }
            val info = requireNotNull(packageInfo.applicationInfo)
            val parent = File(info.nativeLibraryDir).canonicalFile
            val executable = File(parent, LIBRARY)
            check(executable.canonicalFile == executable && !Files.isSymbolicLink(executable.toPath()) &&
                executable.isFile && executable.canExecute()) { "Owned QUIC test-only APK executable unavailable" }
            ZipFile(info.sourceDir).use { apk ->
                val entry = Build.SUPPORTED_ABIS.asSequence().mapNotNull { apk.getEntry("lib/$it/$LIBRARY") }.firstOrNull()
                checkNotNull(entry) { "Owned QUIC APK has no executable for this ABI" }
                check(entry.size in 1L..(16L * 1024 * 1024) && entry.size == executable.length())
                apk.getInputStream(entry).use { expected ->
                    executable.inputStream().use { actual -> check(sha256(expected) == sha256(actual)) }
                }
            }
            return VerifiedClient(helper, info.uid, executable)
        }

        private fun validateCertificate(certificate: String, pin: String) {
            check(certificate.matches(Regex("[A-Za-z0-9_-]{1,2048}")) && pin.matches(Regex("[a-f0-9]{64}")))
            val der = Base64.decode(certificate, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            check(sha256(der.inputStream()) == pin) { "Owned QUIC certificate pin mismatch" }
        }

        fun openWireGuard(context: Context, pin: String, certificate: String, certificateSha256: String): ClientQuicOwnerChecks {
            validateCertificate(certificate, certificateSha256)
            val client = verifyClient(context, pin)
            return ClientQuicOwnerChecks(context, client.helper, client.uid, client.executable, null,
                certificate, certificateSha256, 18444)
        }

        fun open(context: Context, pin: String): ClientQuicOwnerChecks {
            val client = verifyClient(context, pin)
            val server = FixtureProcess(client.executable, "server", "--listen", "127.0.0.1:0")
            try {
                val ready = server.event("ready")
                val port = ready.opt("port") as? Int ?: error("Missing owned QUIC origin port")
                val certificate = ready.opt("certificate") as? String ?: error("Missing owned QUIC certificate")
                val pinSha256 = ready.opt("certificateSha256") as? String ?: error("Missing owned QUIC certificate pin")
                check(port in 1024..65535)
                validateCertificate(certificate, pinSha256)
                return ClientQuicOwnerChecks(context, client.helper, client.uid, client.executable, server,
                    certificate, pinSha256, port)
            } catch (error: Throwable) {
                try { server.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }

        private fun nonce() = UUID.randomUUID().toString().replace("-", "")

        private fun requireExchange(event: JSONObject, nonce: String) {
            check(event.opt("nonce") == nonce && event.opt("quicVersion") == 1 &&
                event.opt("tls") == "1.3" && event.opt("alpn") == ALPN) {
                "Owned QUIC exchange evidence mismatch"
            }
        }

        private fun counts(event: JSONObject): Snapshot {
            val accepted = event.opt("accepted") as? Int ?: error("Missing owned QUIC accepted count")
            val datagrams = event.opt("datagrams")
            check(accepted >= 0 && (datagrams is Int || datagrams is Long))
            val count = (datagrams as Number).toLong()
            check(count >= 0)
            return Snapshot(accepted, count)
        }

        private fun boundedLine(input: InputStream): String {
            val bytes = ByteArray(8192)
            for (index in bytes.indices) {
                val value = input.read()
                check(value >= 0) { "Owned QUIC process exited without evidence" }
                if (value == '\n'.code) return bytes.decodeToString(0, index)
                bytes[index] = value.toByte()
            }
            error("Owned QUIC process produced an oversized event")
        }

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
