package org.mcrf.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONObject

internal class SpeedTestRouteChanged : IOException("SPEEDTEST_ROUTE_CHANGED")
internal class SpeedTestRouteUnavailable : IOException("SPEEDTEST_ROUTE_UNAVAILABLE")

/** Explicit app network/current proxy OR an exact temporary saved-server session.
 * Only openSaved may start an isolated owned child; never a VPN, global selection,
 * process binding or silent fallback. Current proxy does not prove server egress.
 */
internal class SpeedTestRoute private constructor(
    private val manager: ConnectivityManager,
    private val network: Network,
    private val path: SpeedTestPath,
    private val port: Int,
    private val authorization: ByteArray?,
    private val ownerLive: () -> Boolean,
    private val provider: SpeedTestProviderAdapter = SpeedTestProviders.adapter("cloudflare"),
    private val owned: DiagnosticsTemporaryProxy? = null
) : SpeedTestTransport {
    override fun toString() = "SpeedTestRoute(redacted)"
    override fun checkRoute() {
        if (manager.activeNetwork != network || manager.getNetworkCapabilities(network) == null || !ownerLive())
            throw SpeedTestRouteChanged()
    }

    override fun exchange(stage: SpeedTestStage, bytes: Int, token: DiagnosticCancellation, progress: (Long) -> Unit): SpeedTestSample {
        token.check(); checkRoute()
        owned?.let { session ->
            return session.endpoint.connect(provider, token).use { tunnel -> exchangeTls(tunnel.socket, stage, bytes, token, progress) }
        }
        val address = if (path == SpeedTestPath.APP_NETWORK) resolve(token) else InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val raw = if (path == SpeedTestPath.APP_NETWORK) network.socketFactory.createSocket() else Socket(Proxy.NO_PROXY)
        return raw.use {
            val rawLease = token.attach(raw)
            try {
                raw.soTimeout = token.remainingMs()
                raw.connect(InetSocketAddress(address, if (path == SpeedTestPath.APP_NETWORK) 443 else port), token.remainingMs())
                if (path == SpeedTestPath.CURRENT_MCRF_PROXY) {
                    val request = "CONNECT ${provider.host}:443 HTTP/1.1\r\nHost: ${provider.host}:443\r\n".toByteArray(Charsets.US_ASCII)
                    token.check(); raw.outputStream.write(request)
                    authorization?.let { raw.outputStream.write(it) } // Loopback only, never inside the origin TLS request.
                    raw.outputStream.write(byteArrayOf(13, 10)); raw.outputStream.flush()
                    if (DiagnosticsHttp.head(raw, token).status != 200) throw SpeedTestRouteUnavailable()
                }
                token.check(); checkRoute()
                exchangeTls(raw, stage, bytes, token, progress)
            } finally { rawLease.close() }
        }
    }

    private fun exchangeTls(raw: Socket, stage: SpeedTestStage, bytes: Int, token: DiagnosticCancellation,
        progress: (Long) -> Unit): SpeedTestSample {
        token.check(); checkRoute()
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, provider.host, 443, true) as SSLSocket
        tls.use {
            val tlsLease = token.attach(tls)
            try {
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(provider.host))
                }
                tls.soTimeout = token.remainingMs(); tls.startHandshake()
                token.check(); checkRoute()
                return provider.exchange(tls, stage, bytes, token, progress).also { token.check(); checkRoute() }
            } finally { tlsLease.close() }
        }
    }

    private fun resolve(token: DiagnosticCancellation): InetAddress {
        val done = CountDownLatch(1)
        val addresses = AtomicReference<List<InetAddress>>(emptyList())
        val signal = CancellationSignal()
        val lease = token.attach(AutoCloseable { signal.cancel(); done.countDown() })
        try {
            DnsResolver.getInstance().query(network, provider.host, DnsResolver.FLAG_NO_RETRY, Executor { it.run() }, signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (rcode == 0) addresses.set(answer.take(4))
                        done.countDown()
                    }
                    override fun onError(error: DnsResolver.DnsException) { done.countDown() }
                })
            if (!done.await(token.remainingMs().toLong(), TimeUnit.MILLISECONDS)) throw java.net.SocketTimeoutException()
            token.check(); checkRoute()
            return addresses.get().firstOrNull() ?: throw SpeedTestRouteUnavailable()
        } finally { signal.cancel(); lease.close() }
    }

    override fun close() { authorization?.fill(0); owned?.awaitClosed() }

    companion object {
        fun capture(context: Context, path: SpeedTestPath, token: DiagnosticCancellation,
            provider: SpeedTestProviderAdapter = SpeedTestProviders.adapter("cloudflare")): SpeedTestRoute {
            require(path != SpeedTestPath.SAVED_SERVER)
            require(provider === SpeedTestProviders.adapter(provider.id))
            token.check()
            val app = context.applicationContext
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val network = manager.activeNetwork ?: throw SpeedTestRouteUnavailable()
            val selected = McrfVpnService.selectedId
            val session = McrfVpnService.recovery
            val port = McrfVpnService.localProxyPort
            fun live(): Boolean = McrfVpnService.selectedId == selected && McrfVpnService.recovery.epoch == session.epoch &&
                McrfVpnService.recovery.attempt == session.attempt && (path != SpeedTestPath.CURRENT_MCRF_PROXY ||
                (McrfVpnService.localProxyPort == port && McrfVpnService.hasActiveSession() && ConnectionPresentation.connected(McrfVpnService.status)))
            var auth: ByteArray? = null
            try {
                if (path == SpeedTestPath.CURRENT_MCRF_PROXY) {
                    if (port !in 1024..65535 || !live()) throw SpeedTestRouteUnavailable()
                    // Read existing auth, never LocalProxySettings.value (which can generate/write defaults).
                    val value = synchronized(ProfileStore.storageLock) {
                        token.check()
                        LocalProxyConfig.validate(JSONObject(ProfileStore(app, "preferences.enc").read()).getJSONObject("localProxy"))
                    }
                    if (value.getBoolean("enabled")) {
                        val credentials = "${value.getString("username")}:${value.getString("password")}".toByteArray(Charsets.UTF_8)
                        try {
                            auth = ("Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(credentials) + "\r\n").toByteArray(Charsets.US_ASCII)
                        } finally { credentials.fill(0) }
                    }
                }
                token.check()
                return SpeedTestRoute(manager, network, path, port, auth, ::live, provider).also { it.checkRoute() }
            } catch (error: Exception) { auth?.fill(0); throw error }
        }
        fun openSaved(context: Context, server: ServerImport.Server, provider: SpeedTestProviderAdapter,
            token: DiagnosticCancellation): SpeedTestRoute {
            require(provider === SpeedTestProviders.adapter(provider.id))
            token.check()
            val session = DiagnosticsTemporaryProxy.open(context, server, token)
            try {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                val network = manager.activeNetwork ?: throw SpeedTestRouteUnavailable()
                return SpeedTestRoute(manager, network, SpeedTestPath.SAVED_SERVER, 0, null,
                    { runCatching { session.endpoint.check() }.isSuccess }, provider, session).also { it.checkRoute() }
            } catch (error: Throwable) { session.awaitClosed(); throw error }
        }
    }
}
