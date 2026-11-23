package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Captures existing paths only. Never starts/stops a VPN, alters routes, binds the
 * process, writes preferences, installs a core or follows HTTP redirects.
 * MCRF uses its existing mixed listener and therefore current routing rules; this
 * does not assert that every destination used the selected VPN server. */
class RealServiceProbe internal constructor(
    private val direct: Network?,
    private val directLive: () -> Boolean,
    private val proxyPort: Int?,
    private val proxyAuthorization: String?,
    private val proxyLive: () -> Boolean,
    private val socksCredentials: Pair<String, String>? = null,
    private val proxyRunning: () -> Boolean = { true },
    private val proxySocket: () -> Socket = { Socket(Proxy.NO_PROXY) },
    private val tlsSocket: (Socket, String) -> SSLSocket = { raw, host ->
        (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
    }
) : ServiceProbe {
    override fun toString() = "RealServiceProbe(redacted)"
    internal fun available(path: DiagnosticPath) = if (path == DiagnosticPath.DIRECT) direct != null else proxyPort != null
    internal fun live(path: DiagnosticPath) = runCatching { if (path == DiagnosticPath.DIRECT) directLive() else proxyLive() }.getOrDefault(false)
    private fun checkPath(path: DiagnosticPath, token: DiagnosticCancellation) {
        token.check()
        if (!live(path)) throw DiagnosticsPathChanged()
        if (path == DiagnosticPath.CURRENT_MCRF && !proxyRunning())
            throw DiagnosticProxyException(DiagnosticProxyFailure.STOPPED)
    }

    /** Original hostname remains SNI/certificate identity and HTTP Host. Numeric
     * routing may differ, so this does not prove current server egress. */
    internal fun <T> ipv6Https(host: String, address: InetAddress?, path: DiagnosticPath, token: DiagnosticCancellation,
        action: (SSLSocket) -> T): T {
        require(host == "dns.google" || ServiceTestCatalog.targets.any { it.check == DiagnosticCheck.HTTPS && it.host == host })
        require(address == null || address is Inet6Address)
        return https(host, address, path, token, action = action)
    }

    /** Only a dedicated per-server temporary proxy may be used for authoritative egress proof. */
    internal fun exitTrace(token:DiagnosticCancellation):String {
        var failure:IOException?=null
        // Some exit networks block www while the speed hostname remains reachable.
        // Both requests use the SAME selected server and strict TLS hostname validation.
        for(host in listOf("www.cloudflare.com","speed.cloudflare.com")) {
            token.check()
            try {return https(host,null,DiagnosticPath.CURRENT_MCRF,token) {tls->
                tls.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $host\r\nAccept: text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));tls.outputStream.flush()
                WarpTraceTransport.readBody(java.io.BufferedInputStream(tls.inputStream))
            }}catch(e:IOException){token.check();failure=e}
        }
        throw IOException("Сервис IP-теста не ответил через выбранный сервер.",failure)
    }
    private fun <T> https(host: String, address: InetAddress?, path: DiagnosticPath, token: DiagnosticCancellation,
        stage: (DiagnosticStage, Boolean) -> Unit = { _, _ -> }, action: (SSLSocket) -> T): T {
        checkPath(path, token)
        stage(DiagnosticStage.CONNECT, false)
        // use surrounds attach: cancellation between allocation and attachment
        // must close the newly allocated socket too.
        val raw = if (path == DiagnosticPath.DIRECT) direct!!.socketFactory.createSocket() else proxySocket()
        return raw.use {
            val ownership = token.attach(raw)
            try {
                raw.soTimeout = token.remainingMs()
                val destination = if (path == DiagnosticPath.DIRECT) InetSocketAddress(requireNotNull(address), 443)
                    else InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), requireNotNull(proxyPort))
                try { raw.connect(destination, token.remainingMs()) }
                catch (error: ConnectException) {
                    if (path == DiagnosticPath.CURRENT_MCRF) throw DiagnosticProxyException(DiagnosticProxyFailure.CONNECT_REJECTED)
                    throw error
                }
                if (path == DiagnosticPath.CURRENT_MCRF) {
                    val numeric = address?.hostAddress
                    connectProxy(raw, if (numeric == null) host else if (address is Inet6Address) "[$numeric]" else numeric, token)
                }
                checkPath(path, token)
                stage(DiagnosticStage.CONNECT, true)
                stage(DiagnosticStage.TLS, false)
                tlsSocket(raw, host).use { tls ->
                    val tlsOwnership = token.attach(tls)
                    try {
                        tls.sslParameters = tls.sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"; serverNames = listOf(SNIHostName(host))
                        }
                        tls.soTimeout = token.remainingMs(); tls.startHandshake()
                        checkPath(path, token)
                        stage(DiagnosticStage.TLS, true)
                        action(tls).also { checkPath(path, token) }
                    } finally { tlsOwnership.close() }
                }
            } finally { ownership.close() }
        }
    }

    override fun execute(request: DiagnosticRequest, cancellation: DiagnosticCancellation): DiagnosticResult {
        require(ServiceTestCatalog.find(request.target.id) == request.target)
        val started = System.nanoTime()
        val evidence = mutableListOf<DiagnosticEvidence>()
        var stage = DiagnosticStage.ROUTE
        var dnsScope: DiagnosticDnsScope? = null
        var dnsFailure: DiagnosticDnsFailure? = null
        fun result(state: DiagnosticState, issue: DiagnosticIssue = DiagnosticIssue.NONE,
            proxyFailure: DiagnosticProxyFailure? = null): DiagnosticResult {
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            val cancelled = runCatching { cancellation.checkCancellation() }.isFailure
            val changed = state in setOf(DiagnosticState.AVAILABLE, DiagnosticState.ERROR) && !live(request.path)
            // A child can exit while TLS/read is blocked, bypassing the next
            // checkPath. Its resulting I/O error is not evidence against the origin.
            val stopped = !cancelled && !changed && state == DiagnosticState.ERROR &&
                request.path == DiagnosticPath.CURRENT_MCRF && !runCatching(proxyRunning).getOrDefault(false)
            return DiagnosticResult(request.target.id, request.path, request.family,
                if (cancelled) DiagnosticState.CANCELLED else if (changed) DiagnosticState.UNAVAILABLE else state,
                if (cancelled) DiagnosticIssue.CANCELLED else if (changed) DiagnosticIssue.PATH_CHANGED else if (stopped) DiagnosticIssue.INTERNAL else issue,
                elapsed, evidence.toList(), dnsScope,
                if (cancelled || changed) null else if (stopped) DiagnosticProxyFailure.STOPPED else proxyFailure, dnsFailure)
        }
        if (request.target.check == DiagnosticCheck.UNSUPPORTED)
            return result(DiagnosticState.UNAVAILABLE, DiagnosticIssue.UNSUPPORTED_PROTOCOL)
        if (!available(request.path)) return result(DiagnosticState.UNAVAILABLE,
            if (request.path == DiagnosticPath.DIRECT) DiagnosticIssue.NO_DIRECT_NETWORK else DiagnosticIssue.NO_MCRF_PROXY)
        if (request.path == DiagnosticPath.CURRENT_MCRF && request.family != DiagnosticFamily.AUTOMATIC)
            return result(DiagnosticState.UNAVAILABLE, DiagnosticIssue.UNSUPPORTED_PROTOCOL)
        try {
            checkPath(request.path, cancellation)
            evidence.add(DiagnosticEvidence(stage, DiagnosticState.AVAILABLE))
            stage = DiagnosticStage.DNS
            val addresses = when {
                request.path == DiagnosticPath.DIRECT -> {
                    dnsScope = DiagnosticDnsScope.PLATFORM
                    resolve(direct!!, request.target.host, request.family, cancellation)
                }
                request.target.check == DiagnosticCheck.DNS -> {
                    dnsScope = DiagnosticDnsScope.CONTROL_DOH_THROUGH_PROXY
                    dnsFailure = DiagnosticDnsFailure.CONTROL_TRANSPORT
                    DiagnosticsProxyDns.resolve(request.target.host, cancellation) { query ->
                        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
                        https("dns.google", null, request.path, cancellation) { socket ->
                            val head = "GET /dns-query?dns=$encoded HTTP/1.1\r\nHost: dns.google\r\nAccept: application/dns-message\r\nConnection: close\r\n\r\n"
                            socket.outputStream.write(head.toByteArray(Charsets.US_ASCII))
                            DiagnosticsHttp.dnsBody(socket, cancellation)
                        }
                    }.also { dnsFailure = if (it.isEmpty()) DiagnosticDnsFailure.TARGET_NO_ADDRESS else null }
                }
                else -> {
                    // A hostname CONNECT is not an observed DNS wire response.
                    evidence.add(DiagnosticEvidence(stage, DiagnosticState.UNAVAILABLE, DiagnosticIssue.DNS_NOT_OBSERVED))
                    emptyList()
                }
            }
            if (dnsScope != null) {
                val counts = Ipv6Diagnostics.answers(addresses)
                evidence.add(DiagnosticEvidence(stage, if (addresses.isEmpty()) DiagnosticState.ERROR else DiagnosticState.AVAILABLE,
                    if (addresses.isEmpty()) DiagnosticIssue.NO_ADDRESS else DiagnosticIssue.NONE,
                    ipv4Answers = counts.ipv4, ipv6Answers = counts.ipv6))
                if (addresses.isEmpty()) return result(DiagnosticState.ERROR, DiagnosticIssue.NO_ADDRESS)
            }
            if (request.target.check == DiagnosticCheck.DNS) {
                checkPath(request.path, cancellation)
                return result(DiagnosticState.AVAILABLE)
            }
            val destinations: List<InetAddress?> = if (request.path == DiagnosticPath.DIRECT) addresses.take(4) else listOf(null)
            for ((index, address) in destinations.withIndex()) {
                try {
                    var method = "HEAD"
                    while (true) {
                        val status = https(request.target.host, address, request.path, cancellation, { value, complete ->
                            stage = value
                            if (complete) evidence.add(DiagnosticEvidence(value, DiagnosticState.AVAILABLE))
                        }) { socket ->
                            stage = DiagnosticStage.HTTP
                            cancellation.check(); socket.soTimeout = cancellation.remainingMs()
                            socket.outputStream.write(("$method / HTTP/1.1\r\nHost: " + request.target.host + "\r\n" +
                                "Connection: close\r\nUser-Agent: MCRF-ServiceTests\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            DiagnosticsHttp.head(socket, cancellation).status
                        }
                        val state = if (status in 200..399) DiagnosticState.AVAILABLE else DiagnosticState.ERROR
                        val issue = if (state == DiagnosticState.AVAILABLE) DiagnosticIssue.NONE else DiagnosticIssue.HTTP_REJECTED
                        evidence.add(DiagnosticEvidence(DiagnosticStage.HTTP, state, issue, httpStatus = status))
                        // Some origins reject HEAD. Retry exactly once with GET;
                        // consume only bounded headers, then close without reading a body.
                        if (method == "HEAD" && status in setOf(405, 501)) { method = "GET"; continue }
                        return result(state, issue)
                    }
                } catch (error: IOException) {
                    cancellation.checkCancellation()
                    if (error is DiagnosticProxyException || error is SocketTimeoutException || index == destinations.lastIndex) throw error
                    evidence.add(DiagnosticEvidence(stage, DiagnosticState.ERROR,
                        if (error is SSLException) DiagnosticIssue.TLS_FAILED else DiagnosticIssue.CONNECT_FAILED))
                }
            }
            return result(DiagnosticState.ERROR, DiagnosticIssue.CONNECT_FAILED)
        } catch (_: CancellationException) {
            return result(DiagnosticState.CANCELLED, DiagnosticIssue.CANCELLED)
        } catch (_: DiagnosticsPathChanged) {
            return result(DiagnosticState.UNAVAILABLE, DiagnosticIssue.PATH_CHANGED)
        } catch (error: DiagnosticProxyException) {
            evidence.add(DiagnosticEvidence(stage, DiagnosticState.ERROR, DiagnosticIssue.INTERNAL))
            return result(DiagnosticState.ERROR, DiagnosticIssue.INTERNAL, error.failure)
        } catch (error: DiagnosticsProxyDns.Failure) {
            dnsFailure = error.kind
            val issue = if (error.kind == DiagnosticDnsFailure.TARGET_NO_ADDRESS) DiagnosticIssue.NO_ADDRESS else DiagnosticIssue.DNS_FAILED
            evidence.add(DiagnosticEvidence(DiagnosticStage.DNS, DiagnosticState.ERROR, issue,
                httpStatus = error.httpStatus, dnsRcode = error.rcode))
            return result(DiagnosticState.ERROR, issue)
        } catch (_: SocketTimeoutException) {
            evidence.add(DiagnosticEvidence(stage, DiagnosticState.ERROR, DiagnosticIssue.TIMEOUT))
            return result(DiagnosticState.ERROR, DiagnosticIssue.TIMEOUT)
        } catch (_: SSLException) {
            evidence.add(DiagnosticEvidence(stage, DiagnosticState.ERROR, DiagnosticIssue.TLS_FAILED))
            return result(DiagnosticState.ERROR, DiagnosticIssue.TLS_FAILED)
        } catch (_: Exception) {
            val issue = when (stage) {
                DiagnosticStage.DNS -> DiagnosticIssue.DNS_FAILED
                DiagnosticStage.HTTP -> DiagnosticIssue.HTTP_REJECTED
                else -> DiagnosticIssue.CONNECT_FAILED
            }
            evidence.add(DiagnosticEvidence(stage, DiagnosticState.ERROR, issue))
            return result(DiagnosticState.ERROR, issue)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(network: Network, host: String, family: DiagnosticFamily, token: DiagnosticCancellation): List<InetAddress> {
        val done = CountDownLatch(1)
        val signal = CancellationSignal()
        val ownership = token.attach(AutoCloseable { signal.cancel(); done.countDown() })
        var addresses = emptyList<InetAddress>()
        var failed = false
        val callback = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) { addresses = answer; failed = rcode != 0; done.countDown() }
            override fun onError(error: DnsResolver.DnsException) { failed = true; done.countDown() }
        }
        try {
            token.check()
            if (family == DiagnosticFamily.AUTOMATIC)
                DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_NO_RETRY, Executor { it.run() }, signal, callback)
            else DnsResolver.getInstance().query(network, host, if (family == DiagnosticFamily.IPV6) DnsResolver.TYPE_AAAA else DnsResolver.TYPE_A,
                DnsResolver.FLAG_NO_RETRY, Executor { it.run() }, signal, callback)
            while (!done.await(minOf(25, token.remainingMs()).toLong(), TimeUnit.MILLISECONDS)) token.check()
            token.check()
            if (failed) throw UnknownHostException()
            return Ipv6Diagnostics.select(addresses, family)
        } finally { signal.cancel(); ownership.close() }
    }

    private fun connectProxy(socket: Socket, host: String, token: DiagnosticCancellation) {
        socksCredentials?.let { (user, password) ->
            DiagnosticsSocksConnect.connect(socket, host, user, password, token)
            return
        }
        // Credentials go only to the owned loopback listener, never to origins
        // or a process-wide Authenticator.
        val auth = proxyAuthorization?.let { "Proxy-Authorization: Basic $it\r\n" }.orEmpty()
        val bytes = "CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n$auth\r\n".toByteArray(Charsets.US_ASCII)
        try { token.check(); socket.outputStream.write(bytes) } finally { bytes.fill(0) }
        val head = try { DiagnosticsHttp.head(socket, token) }
        catch (error: IOException) {
            if (error is SocketTimeoutException) throw error
            throw DiagnosticProxyException(DiagnosticProxyFailure.PROTOCOL)
        }
        if (head.status == 407) throw DiagnosticProxyException(DiagnosticProxyFailure.AUTHENTICATION)
        if (head.status !in 200..299) throw DiagnosticProxyException(DiagnosticProxyFailure.CONNECT_REJECTED)
    }

    companion object {
        /** Read auth directly: LocalProxySettings.value may generate/write defaults. */
        fun capture(context: Context): RealServiceProbe {
            val app = context.applicationContext
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val network = runCatching { DirectAccessCheck.physical(app) }.getOrNull()
            val selected = McrfVpnService.selectedId
            val session = McrfVpnService.recovery
            val port = McrfVpnService.localProxyPort.takeIf {
                it in 1024..65535 && McrfVpnService.hasActiveSession() && ConnectionPresentation.connected(McrfVpnService.status)
            }
            val auth = if (port == null) null else runCatching {
                synchronized(ProfileStore.storageLock) {
                    val raw = ProfileStore(app, "preferences.enc").read()
                    val value = JSONObject(raw).optJSONObject("localProxy") ?: throw IOException()
                    LocalProxyConfig.validate(value)
                }
            }.getOrNull()
            val usablePort = port.takeIf { auth != null }
            val authorization = auth?.takeIf { it.getBoolean("enabled") }?.let {
                val bytes = "${it.getString("username")}:${it.getString("password")}".toByteArray(Charsets.UTF_8)
                try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
            }
            return RealServiceProbe(network, {
                runCatching { network != null && manager.getNetworkCapabilities(network) != null && DirectAccessCheck.physical(app) == network }.getOrDefault(false)
            }, usablePort, authorization, {
                usablePort != null && McrfVpnService.localProxyPort == usablePort && McrfVpnService.selectedId == selected &&
                    McrfVpnService.recovery.epoch == session.epoch && McrfVpnService.recovery.attempt == session.attempt &&
                    McrfVpnService.hasActiveSession() && ConnectionPresentation.connected(McrfVpnService.status)
            })
        }
    }
}
