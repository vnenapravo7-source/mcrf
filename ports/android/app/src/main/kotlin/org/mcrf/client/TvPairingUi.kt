package org.mcrf.client

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.text.InputFilter
import android.text.InputType
import android.view.View
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

internal object TvPairingUi {
    private const val TYPE = "_mcrf-tv._tcp."
    private const val RECEIVE_TIMEOUT_MS = 120_000L
    private const val DISCOVERY_TIMEOUT_MS = 18_000L

    private fun isLocal(peer: InetAddress, source: InetAddress? = null): Boolean {
        if (peer !is Inet4Address || !peer.isSiteLocalAddress) return false
        return LanSharingAddresses.discover().any { local ->
            val address = InetAddress.getByName(local)
            if (source != null && address != source) return@any false
            NetworkInterface.getByInetAddress(address)?.interfaceAddresses.orEmpty().any localLink@ { link ->
                if (link.address != address || link.networkPrefixLength !in 1..32) return@localLink false
                val bits = link.networkPrefixLength.toInt()
                val mask = -1 shl (32 - bits)
                fun number(ip: InetAddress) = java.nio.ByteBuffer.wrap(ip.address).int
                (number(address) and mask) == (number(peer) and mask)
            }
        }
    }

    fun receive(activity: MainActivity) = with(activity) {
        val address = LanSharingAddresses.discover().firstOrNull()
        if (address == null) {
            alert("Передача с Android", "Подключите телевизор к локальной сети.")
            return@with
        }
        val server = runCatching {
            ServerSocket().apply {
                try {
                    bind(InetSocketAddress(InetAddress.getByName(address), 0))
                    soTimeout = 1000
                } catch (error: Exception) {
                    close()
                    throw error
                }
            }
        }.getOrElse {
            alert("Передача с Android", "Не удалось открыть приём на телевизоре.")
            return@with
        }
        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val code = TvPairingWire.newCode()
        val body = card().apply {
            addView(text("На телефоне выберите подключение и нажмите «Отправить на ТВ». Введите код с этого экрана.", 14, Loft.INK))
            addView(text(code, 32, Loft.INK).apply {
                gravity = android.view.Gravity.CENTER
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, space(14, 10))
            addView(text("Код действует 2 минуты. После передачи подтвердите импорт на телевизоре.", 12, Loft.MUTED))
        }
        val stopped = AtomicBoolean()
        val dialog = ThemedDialogBuilder(this)
            .setTitle("Принять с Android").setView(body).setNegativeButton("Закрыть", null).create()
        lateinit var registered: NsdManager.RegistrationListener
        var client: Socket? = null
        val lock = Any()
        fun close() {
            if (!stopped.compareAndSet(false, true)) return
            runCatching { manager.unregisterService(registered) }
            runCatching { server.close() }
            synchronized(lock) { runCatching { client?.close() } }
        }
        fun finish(message: String?, profile: String? = null) = runOnUiThread {
            if (!dialog.isShowing || isFinishing || isDestroyed) return@runOnUiThread
            dialog.dismiss()
            if (profile != null) {
                runCatching { TvTransferredProfile.decode(profile) }
                    .onSuccess { importTvProfile(it) }
                    .onFailure { alert("Передача с Android", "Профиль не распознан. Проверьте версию MCRF на телефоне.") }
            }
            else if (message != null) alert("Передача с Android", message)
        }
        registered = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(service: NsdServiceInfo) {
                if (stopped.get()) runCatching { manager.unregisterService(this) }
            }
            override fun onRegistrationFailed(service: NsdServiceInfo, code: Int) =
                finish("Не удалось объявить телевизор в локальной сети.")
            override fun onServiceUnregistered(service: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(service: NsdServiceInfo, code: Int) = Unit
        }
        val expiryHandler = android.os.Handler(mainLooper)
        val expiry = Runnable { close(); finish("Время действия кода истекло. Откройте приём снова.") }
        dialog.setOnDismissListener { expiryHandler.removeCallbacks(expiry); close() }
        expiryHandler.postDelayed(expiry, RECEIVE_TIMEOUT_MS)
        dialog.show()
        val info = NsdServiceInfo().apply {
            serviceName = "MCRF TV"
            serviceType = TYPE
            port = server.localPort
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registered)
        } catch (_: Exception) {
            finish("Не удалось запустить обнаружение в локальной сети.")
            return@with
        }
        Thread({
            val expires = android.os.SystemClock.elapsedRealtime() + RECEIVE_TIMEOUT_MS
            var attempts = 0
            try {
                while (!stopped.get() && android.os.SystemClock.elapsedRealtime() < expires && attempts < 5) {
                    val incoming = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    val profile = incoming.use { socket ->
                        if (!isLocal(socket.inetAddress)) return@use null
                        synchronized(lock) {
                            if (stopped.get()) return@use null
                            client = socket
                        }
                        attempts++
                        try { runCatching { TvPairingWire.exchange(socket, code, null) }.getOrNull() }
                        finally { synchronized(lock) { client = null } }
                    }
                    if (profile != null) {
                        finish(null, profile)
                        return@Thread
                    }
                }
                finish("Время действия кода истекло или исчерпаны попытки. Откройте приём снова.")
            } catch (_: Exception) {
                if (!stopped.get()) finish("Локальная передача прервалась. Попробуйте снова.")
            } finally { close() }
        }, "mcrf-tv-receive").start()
    }

    fun send(activity: MainActivity, profile: String) = with(activity) {
        val input = editor("Код с экрана ТВ").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            setSingleLine(true)
        }
        val entry = ThemedDialogBuilder(this).setTitle("Отправить на ТВ")
            .setView(card().apply { addView(input) })
            .setNegativeButton("Отмена", null).setPositiveButton("Отправить", null).create()
        entry.setOnShowListener {
            entry.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString()
                if (code.length != 6 || code.any { it !in '0'..'9' }) {
                    input.error = "Введите шесть цифр"
                    return@setOnClickListener
                }
                entry.dismiss()
                discover(activity, code, profile)
            }
        }
        entry.show()
    }

    @Suppress("DEPRECATION")
    private fun discover(activity: MainActivity, code: String, profile: String) = with(activity) {
        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val handler = android.os.Handler(mainLooper)
        val pending = ArrayDeque<NsdServiceInfo>()
        val seen = HashSet<String>()
        val canceled = AtomicBoolean()
        val dialog = ThemedDialogBuilder(this).setTitle("Поиск телевизора")
            .setMessage("Держите экран с кодом открытым. Оба устройства должны быть в одной сети.")
            .setNegativeButton("Отмена", null).create()
        var busy = false
        var socket: Socket? = null
        lateinit var listener: NsdManager.DiscoveryListener
        fun finish(message: String) {
            if (!dialog.isShowing || isDestroyed || isFinishing) return
            dialog.dismiss()
            alert("Передача на ТВ", message)
        }
        fun next() {
            if (busy || canceled.get() || pending.isEmpty()) return
            busy = true
            try {
                manager.resolveService(pending.removeFirst(), object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, error: Int) = handler.post {
                        busy = false
                        next()
                    }.let { Unit }
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        handler.post {
                            if (canceled.get()) return@post
                            val candidate = Socket()
                            socket = candidate
                            Thread({
                                val sent = candidate.use {
                                    runCatching {
                                        val host = service.host ?: error("No LAN address")
                                        require(isLocal(host))
                                        val connectivity = getSystemService(ConnectivityManager::class.java)
                                        val network = connectivity.allNetworks.firstOrNull { candidateNetwork ->
                                            val capabilities = connectivity.getNetworkCapabilities(candidateNetwork)
                                            capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                                                connectivity.getLinkProperties(candidateNetwork)?.linkAddresses.orEmpty().any { isLocal(host, it.address) }
                                        } ?: error("No physical LAN route")
                                        network.bindSocket(candidate)
                                        candidate.connect(InetSocketAddress(host, service.port), 2500)
                                        TvPairingWire.exchange(candidate, code, profile)
                                    }.isSuccess
                                }
                                handler.post {
                                    socket = null
                                    busy = false
                                    if (sent) finish("Подключение передано. Подтвердите импорт на телевизоре.")
                                    else next()
                                }
                            }, "mcrf-tv-send").start()
                        }
                    }
                })
            } catch (_: Exception) { busy = false }
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                handler.post { finish("Не удалось начать поиск в локальной сети.") }
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                handler.post {
                    if (!canceled.get() && service.serviceType.equals(TYPE, true) &&
                        seen.size < 8 && seen.add(service.serviceName)) {
                        pending.addLast(service)
                        next()
                    }
                }
            }
        }
        val timeout = Runnable { finish("Подключение не передано. Проверьте код, сеть и открытый экран приёма на ТВ.") }
        dialog.setOnDismissListener {
            canceled.set(true)
            handler.removeCallbacks(timeout)
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { socket?.close() }
        }
        dialog.show()
        handler.postDelayed(timeout, DISCOVERY_TIMEOUT_MS)
        try { manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: Exception) { finish("Не удалось начать поиск в локальной сети.") }
    }
}
