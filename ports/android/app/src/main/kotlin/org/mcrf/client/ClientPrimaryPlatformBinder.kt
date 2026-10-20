package org.mcrf.client

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.mcrf.engine.spi.v1.Platform

/** Private same-UID protocol. Every fd on the wire is a Binder file-descriptor object. */
object ClientPrimaryPlatformBinder {
    private const val DESCRIPTOR = "org.mcrf.client.PrimaryHostPlatform.v1"
    private const val CALLBACK_DESCRIPTOR = "$DESCRIPTOR.Callback"
    private const val MAX_PARCEL = 288 * 1024
    private const val MAX_JSON = 256 * 1024
    private const val MAX_TEXT = 8192
    private const val MAX_LIST = 256
    private const val ATTACH = IBinder.FIRST_CALL_TRANSACTION
    private const val OPEN_TUN = ATTACH + 1
    private const val INTERFACES = ATTACH + 2
    private const val OWNER = ATTACH + 3
    private const val DNS = ATTACH + 4
    private const val CANCEL_DNS = ATTACH + 5
    private const val START_DEFAULT = ATTACH + 6
    private const val CLOSE_DEFAULT = ATTACH + 7
    private const val PROTECT = ATTACH + 8
    private const val FEATURES = ATTACH + 9
    private const val WIFI = ATTACH + 10
    private const val CLEAR_CACHE = ATTACH + 11
    private const val NOTICE = ATTACH + 12
    private const val CANCEL_NOTICE = ATTACH + 13
    private const val START_NEIGHBORS = ATTACH + 14
    private const val CLOSE_NEIGHBORS = ATTACH + 15
    private const val REGISTER = ATTACH + 16
    private const val HOSTNAME = ATTACH + 17
    private const val TRAFFIC = ATTACH + 18
    private const val RELEASE = ATTACH + 19
    private const val DEFAULT_UPDATE = IBinder.FIRST_CALL_TRANSACTION
    private const val NEIGHBOR_UPDATE = DEFAULT_UPDATE + 1
    private const val RETIRED = DEFAULT_UPDATE + 2

    fun stub(platform: Platform, generation: Long): IBinder {
        require(generation > 0) { "PLATFORM_GENERATION" }
        return HostBinder(platform, generation)
    }

    fun proxy(binder: IBinder, generation: Long): Platform {
        require(generation > 0) { "PLATFORM_GENERATION" }
        return WorkerProxy(binder, generation)
    }

    /** Close after native use ends. The fd returned by openTun is borrowed, never detached. */
    fun closeProxy(platform: Platform) { (platform as? WorkerProxy)?.close() }

    private fun requestLimit(code: Int): Int = when (code) {
        OPEN_TUN -> MAX_PARCEL
        DNS -> 66 * 1024
        NOTICE -> 25 * 1024
        CANCEL_NOTICE, REGISTER -> 9 * 1024
        OWNER -> 1024
        else -> 512
    }

    private fun checkCaller(data: Parcel, maximum: Int) {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("PLATFORM_UID")
        require(data.dataSize() in 0..maximum) { "PLATFORM_PARCEL_SIZE" }
    }

    private class HostBinder(private val platform: Platform, private val generation: Long) : Binder() {
        private val lock = Any()
        private val monitorCommands = Any()
        private val closed = AtomicBoolean()
        private var peer: IBinder? = null
        private val peerDeath = IBinder.DeathRecipient { retire(true) }
        private var defaultId = 0L
        private var lastDefaultId = 0L
        private var neighborId = 0L
        private var lastNeighborId = 0L
        private val dnsRequests = HashSet<Long>()
        private val defaultEvent = AtomicReference<DefaultEvent?>()
        private val neighborEvent = AtomicReference<NeighborEvent?>()
        private val pumping = AtomicBoolean()
        private val callbacks = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1))

        init { (platform as? ClientPrimaryPlatformLifetime)?.onClose { retire(false) } }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // This is deliberately BEFORE enforceInterface, DTO decoding, or FD unmarshalling.
            checkCaller(data, requestLimit(code))
            if (code == IBinder.INTERFACE_TRANSACTION) {
                end(data)
                reply?.writeString(DESCRIPTOR)
                return true
            }
            if (code !in ATTACH..RELEASE) return false
            require(flags and IBinder.FLAG_ONEWAY == 0 && reply != null) { "PLATFORM_SYNC_REQUIRED" }
            try {
                data.enforceInterface(DESCRIPTOR)
                require(long(data) == generation) { "PLATFORM_GENERATION" }
                if (code != RELEASE) check(!closed.get()) { "PLATFORM_RETIRED" }
                if (code != ATTACH && code != RELEASE) synchronized(lock) { check(peer != null) { "PLATFORM_UNATTACHED" } }
                // Status is private and fixed; no exception text/stack supplied by a module
                // or Android service is serialized back into this protocol.
                reply.writeInt(0)
                when (code) {
                    ATTACH -> {
                        val token = data.readStrongBinder() ?: throw IOException("PLATFORM_CALLBACK")
                        end(data)
                        synchronized(lock) {
                            check(!closed.get() && peer == null) { "PLATFORM_ALREADY_ATTACHED" }
                            token.linkToDeath(peerDeath, 0)
                            peer = token
                        }
                    }
                    OPEN_TUN -> {
                        val json = text(data, MAX_JSON)
                        end(data)
                        val plan = ClientPrimaryRuntimeWire.decodeTunPlan(json)
                        val hostFd = platform.openTun(generation, plan)
                        // fromFd duplicates the main-owned TUN. writeFileDescriptor transfers
                        // another real descriptor; the host's original is never relinquished.
                        ParcelFileDescriptor.fromFd(hostFd).use { duplicate ->
                            check(!closed.get()) { "PLATFORM_RETIRED" }
                            reply.writeFileDescriptor(duplicate.fileDescriptor)
                        }
                    }
                    INTERFACES -> {
                        end(data)
                        writeList(reply, platform.interfaces(generation)) { value ->
                            writeText(reply, value.name); reply.writeInt(value.index); reply.writeInt(value.mtu)
                            reply.writeInt(value.flags); writeText(reply, value.type); writeBool(reply, value.metered)
                            writeStrings(reply, value.addresses); writeStrings(reply, value.dnsServers)
                        }
                    }
                    OWNER -> {
                        val query = Platform.ConnectionQuery(int(data), text(data, 128), int(data), text(data, 128), int(data))
                        end(data)
                        val value = platform.connectionOwner(generation, query)
                        reply.writeInt(value.uid); writeStrings(reply, value.packageNames)
                    }
                    DNS -> {
                        val id = long(data)
                        val timeout = int(data)
                        val query = Platform.DnsQuery(id, bytes(data, 65535, 12), timeout)
                        end(data)
                        synchronized(lock) {
                            check(!closed.get() && dnsRequests.size < ClientPrimaryRuntimeDnsGate.MAX_IN_FLIGHT && dnsRequests.add(id)) {
                                "PLATFORM_DNS_LIMIT"
                            }
                        }
                        val value = try { platform.exchangeDns(generation, query) }
                            finally { synchronized(lock) { dnsRequests.remove(id) } }
                        reply.writeInt(value.errno); reply.writeInt(value.rcode); writeBytes(reply, value.packet(), 65535)
                    }
                    CANCEL_DNS -> {
                        val id = long(data); require(id > 0) { "PLATFORM_DNS_ID" }; end(data)
                        platform.cancelDns(generation, id)
                    }
                    START_DEFAULT -> {
                        val id = long(data); end(data)
                        synchronized(monitorCommands) {
                            synchronized(lock) {
                                check(!closed.get() && id > lastDefaultId) { "PLATFORM_MONITOR_ID" }
                                defaultId = id; lastDefaultId = id
                            }
                            try {
                                platform.startDefaultMonitor(generation) { callbackGeneration, value ->
                                    if (callbackGeneration == generation && currentDefault(id)) {
                                        defaultEvent.set(DefaultEvent(id, value)); pump()
                                    }
                                }
                            } catch (error: Exception) {
                                synchronized(lock) { if (defaultId == id) defaultId = 0 }
                                throw error
                            }
                        }
                    }
                    CLOSE_DEFAULT -> {
                        val id = long(data); require(id >= 0) { "PLATFORM_MONITOR_ID" }; end(data)
                        synchronized(monitorCommands) {
                            val current = synchronized(lock) {
                                // Also remember close-before-start ordering, without allocating
                                // a tombstone map. Subscription IDs never wrap or repeat.
                                lastDefaultId = maxOf(lastDefaultId, id)
                                (id != 0L && defaultId == id).also { matches ->
                                    if (matches) { defaultId = 0; defaultEvent.set(null) }
                                }
                            }
                            if (current) platform.closeDefaultMonitor(generation)
                        }
                    }
                    PROTECT -> {
                        val duplicate = data.readFileDescriptor() ?: throw IOException("PLATFORM_SOCKET_FD")
                        duplicate.use {
                            end(data)
                            // This fd is valid HERE. No worker integer is ever interpreted
                            // as a main-process fd, and ACK follows protect AND bind.
                            writeBool(reply, platform.protectAndBind(generation, it.fd) && !closed.get())
                        }
                    }
                    FEATURES -> { end(data); writeFeatures(reply, platform.features(generation)) }
                    WIFI -> { end(data); val value = platform.wifiState(generation); writeText(reply, value.ssid); writeText(reply, value.bssid) }
                    CLEAR_CACHE -> { end(data); platform.clearDnsCache(generation) }
                    NOTICE -> {
                        val value = Platform.Notice(text(data), int(data), text(data), text(data)); end(data)
                        platform.notification(generation, value)
                    }
                    CANCEL_NOTICE -> {
                        val id = text(data); val type = int(data); end(data)
                        platform.cancelNotification(generation, id, type)
                    }
                    START_NEIGHBORS -> {
                        val id = long(data); end(data)
                        synchronized(monitorCommands) {
                            synchronized(lock) {
                                check(!closed.get() && id > lastNeighborId) { "PLATFORM_MONITOR_ID" }
                                neighborId = id; lastNeighborId = id
                            }
                            try {
                                platform.startNeighborMonitor(generation) { callbackGeneration, values ->
                                    if (callbackGeneration == generation && currentNeighbor(id)) {
                                        require(values.size <= MAX_LIST) { "PLATFORM_LIST_SIZE" }
                                        neighborEvent.set(NeighborEvent(id, values.toList())); pump()
                                    }
                                }
                            } catch (error: Exception) {
                                synchronized(lock) { if (neighborId == id) neighborId = 0 }
                                throw error
                            }
                        }
                    }
                    CLOSE_NEIGHBORS -> {
                        val id = long(data); require(id >= 0) { "PLATFORM_MONITOR_ID" }; end(data)
                        synchronized(monitorCommands) {
                            val current = synchronized(lock) {
                                lastNeighborId = maxOf(lastNeighborId, id)
                                (id != 0L && neighborId == id).also { matches ->
                                    if (matches) { neighborId = 0; neighborEvent.set(null) }
                                }
                            }
                            if (current) platform.closeNeighborMonitor(generation)
                        }
                    }
                    REGISTER -> { val name = text(data); end(data); platform.registerInterface(generation, name) }
                    HOSTNAME -> { end(data); writeText(reply, platform.hostname(generation)) }
                    TRAFFIC -> {
                        val tx = long(data); val rx = long(data); require(tx >= 0 && rx >= 0) { "PLATFORM_TRAFFIC" }; end(data)
                        platform.traffic(generation, tx, rx)
                    }
                    RELEASE -> { end(data); retire(true) }
                }
                require(reply.dataSize() <= MAX_PARCEL) { "PLATFORM_PARCEL_SIZE" }
            } catch (_: Exception) {
                reply.setDataSize(0); reply.setDataPosition(0)
                reply.writeInt(1); writeText(reply, "PLATFORM_CALL_REJECTED")
            }
            return true
        }

        private fun currentDefault(id: Long) = synchronized(lock) { !closed.get() && defaultId == id }
        private fun currentNeighbor(id: Long) = synchronized(lock) { !closed.get() && neighborId == id }

        /** One queued drain and at most one retained update per monitor, even for a slow peer. */
        private fun pump() {
            if (closed.get() || !pumping.compareAndSet(false, true)) return
            try {
                callbacks.execute {
                    try {
                        while (!closed.get()) {
                            val default = defaultEvent.getAndSet(null)
                            val neighbors = neighborEvent.getAndSet(null)
                            if (default == null && neighbors == null) break
                            if (default != null && currentDefault(default.id)) send(DEFAULT_UPDATE) {
                                it.writeLong(default.id); writeDefault(it, default.value)
                            }
                            if (neighbors != null && currentNeighbor(neighbors.id)) send(NEIGHBOR_UPDATE) {
                                it.writeLong(neighbors.id)
                                writeList(it, neighbors.values) { value ->
                                    writeText(it, value.address); writeText(it, value.mac); writeText(it, value.interfaceName)
                                }
                            }
                        }
                    } catch (_: Exception) { retire(true) }
                    finally {
                        pumping.set(false)
                        if (defaultEvent.get() != null || neighborEvent.get() != null) pump()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { pumping.set(false) }
        }

        private fun send(code: Int, payload: (Parcel) -> Unit = {}) {
            val token = synchronized(lock) { peer } ?: return
            val parcel = Parcel.obtain()
            try {
                parcel.writeInterfaceToken(CALLBACK_DESCRIPTOR); parcel.writeLong(generation)
                payload(parcel)
                require(parcel.dataSize() <= MAX_PARCEL) { "PLATFORM_PARCEL_SIZE" }
                if (!token.transact(code, parcel, null, IBinder.FLAG_ONEWAY)) throw IOException("PLATFORM_CALLBACK_DEAD")
            } finally { parcel.recycle() }
        }

        private fun retire(closePlatform: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            val token: IBinder?
            val dns: List<Long>
            synchronized(lock) {
                defaultId = 0; neighborId = 0
                defaultEvent.set(null); neighborEvent.set(null)
                token = peer
                dns = dnsRequests.toList(); dnsRequests.clear()
            }
            // A retirement notification also closes an already-returned worker TUN duplicate.
            try { send(RETIRED) } catch (_: Exception) { }
            synchronized(lock) { peer = null }
            if (token != null) try { token.unlinkToDeath(peerDeath, 0) } catch (_: Exception) { }
            callbacks.shutdownNow()
            if (closePlatform) {
                if (platform is AutoCloseable) try { platform.close() } catch (_: Exception) { }
                else {
                    dns.forEach { try { platform.cancelDns(generation, it) } catch (_: Exception) { } }
                    try { platform.closeDefaultMonitor(generation) } catch (_: Exception) { }
                    try { platform.closeNeighborMonitor(generation) } catch (_: Exception) { }
                }
            }
        }
    }

    private class WorkerProxy(private val remote: IBinder, private val generation: Long) : Platform, AutoCloseable {
        private val lock = Any()
        private val closed = AtomicBoolean()
        private var tun: ParcelFileDescriptor? = null
        private var tunRequested = false
        private var nextMonitorId = 0L
        private var defaultListener: DefaultRegistration? = null
        private var neighborListener: NeighborRegistration? = null
        private val death = IBinder.DeathRecipient { retire(false) }
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                checkCaller(data, if (code == NEIGHBOR_UPDATE) MAX_PARCEL else 10 * 1024)
                if (code == IBinder.INTERFACE_TRANSACTION) { end(data); reply?.writeString(CALLBACK_DESCRIPTOR); return true }
                if (code !in DEFAULT_UPDATE..RETIRED) return false
                try {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    if (long(data) != generation) return true
                    when (code) {
                        DEFAULT_UPDATE -> {
                            val id = long(data); val value = readDefault(data); end(data)
                            synchronized(lock) {
                                val registration = defaultListener
                                if (!closed.get() && registration?.id == id) registration.listener.update(generation, value)
                            }
                        }
                        NEIGHBOR_UPDATE -> {
                            val id = long(data)
                            val values = readList(data) { Platform.Neighbor(text(data), text(data), text(data)) }; end(data)
                            synchronized(lock) {
                                val registration = neighborListener
                                if (!closed.get() && registration?.id == id) registration.listener.update(generation, values)
                            }
                        }
                        RETIRED -> { end(data); retire(false) }
                    }
                } catch (_: Exception) { retire(false) }
                return true
            }
        }

        init {
            try {
                remote.linkToDeath(death, 0)
                call(ATTACH, generation, { it.writeStrongBinder(callback) }) { Unit }
                check(!closed.get()) { "PLATFORM_RETIRED" }
            } catch (error: Exception) { retire(false); throw error }
        }

        private fun checkLive(value: Long) {
            if (value != generation || closed.get()) throw IOException("PLATFORM_RETIRED")
        }

        private fun <T> call(code: Int, value: Long, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
            if (code != RELEASE) checkLive(value) else require(value == generation)
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeInterfaceToken(DESCRIPTOR); request.writeLong(value); write(request)
                require(request.dataSize() <= requestLimit(code)) { "PLATFORM_PARCEL_SIZE" }
                if (!remote.transact(code, request, response, 0)) throw IOException("PLATFORM_PEER_DEAD")
                require(response.dataSize() in 4..MAX_PARCEL) { "PLATFORM_PARCEL_SIZE" }
                if (int(response) != 0) { text(response, 128); end(response); throw IOException("PLATFORM_CALL_REJECTED") }
                val result = read(response)
                end(response)
                return result
            } catch (error: IOException) { throw error }
            catch (_: Exception) { throw IOException("PLATFORM_TRANSPORT") }
            finally { request.recycle(); response.recycle() }
        }

        private fun unit(code: Int, value: Long, write: (Parcel) -> Unit = {}) = call(code, value, write) { Unit }

        override fun openTun(generation: Long, plan: Platform.TunPlan): Int {
            checkLive(generation)
            val json = ClientPrimaryRuntimeWire.encodeTunPlan(plan)
            synchronized(lock) {
                checkLive(generation)
                check(!tunRequested) { "PLATFORM_TUN_ALREADY_REQUESTED" }
                tunRequested = true
            }
            val duplicate = call(OPEN_TUN, generation, { writeText(it, json, MAX_JSON) }) {
                val fd = it.readFileDescriptor() ?: throw IOException("PLATFORM_TUN_FD")
                try { end(it); fd } catch (error: Exception) { fd.close(); throw error }
            }
            synchronized(lock) {
                if (closed.get()) { duplicate.close(); throw IOException("PLATFORM_RETIRED") }
                tun = duplicate
                return duplicate.fd
            }
        }

        override fun protectAndBind(generation: Long, borrowedSocketFd: Int): Boolean = try {
            checkLive(generation)
            ParcelFileDescriptor.fromFd(borrowedSocketFd).use { duplicate ->
                val protected = call(PROTECT, generation, { it.writeFileDescriptor(duplicate.fileDescriptor) }, ::bool)
                protected && !closed.get()
            }
        } catch (_: Exception) { false }

        override fun interfaces(generation: Long): List<Platform.InterfaceInfo> = call(INTERFACES, generation) { parcel ->
            readList(parcel) { Platform.InterfaceInfo(text(parcel), int(parcel), int(parcel), int(parcel), text(parcel),
                bool(parcel), strings(parcel), strings(parcel)) }
        }
        override fun connectionOwner(generation: Long, query: Platform.ConnectionQuery): Platform.ConnectionOwner =
            call(OWNER, generation, {
                it.writeInt(query.protocol); writeText(it, query.source, 128); it.writeInt(query.sourcePort)
                writeText(it, query.destination, 128); it.writeInt(query.destinationPort)
            }) { Platform.ConnectionOwner(int(it), strings(it)) }

        override fun exchangeDns(generation: Long, query: Platform.DnsQuery): Platform.DnsReply {
            val reply = call(DNS, generation, {
                it.writeLong(query.requestId); it.writeInt(query.timeoutMillis); writeBytes(it, query.packet(), 65535)
            }) { parcel ->
                val errno = int(parcel); val rcode = int(parcel)
                Platform.DnsReply(bytes(parcel, 65535), errno, rcode)
            }
            checkLive(generation)
            return reply
        }
        override fun cancelDns(generation: Long, requestId: Long) {
            if (generation != this.generation || requestId <= 0 || closed.get()) return
            try { unit(CANCEL_DNS, generation) { it.writeLong(requestId) } } catch (_: IOException) { }
        }
        override fun startDefaultMonitor(generation: Long, listener: Platform.DefaultListener) {
            val id = synchronized(lock) {
                checkLive(generation)
                check(nextMonitorId < Long.MAX_VALUE) { "PLATFORM_MONITOR_ID" }
                (++nextMonitorId).also { defaultListener = DefaultRegistration(it, listener) }
            }
            try { unit(START_DEFAULT, generation) { it.writeLong(id) } }
            catch (error: Exception) {
                synchronized(lock) { if (defaultListener?.id == id) defaultListener = null }
                throw error
            }
        }
        override fun closeDefaultMonitor(generation: Long) {
            val id = synchronized(lock) {
                if (generation != this.generation || closed.get()) return
                (defaultListener?.id ?: 0L).also { defaultListener = null }
            }
            try { unit(CLOSE_DEFAULT, generation) { it.writeLong(id) } } catch (_: IOException) { }
        }
        override fun startNeighborMonitor(generation: Long, listener: Platform.NeighborListener) {
            val id = synchronized(lock) {
                checkLive(generation)
                check(nextMonitorId < Long.MAX_VALUE) { "PLATFORM_MONITOR_ID" }
                (++nextMonitorId).also { neighborListener = NeighborRegistration(it, listener) }
            }
            try { unit(START_NEIGHBORS, generation) { it.writeLong(id) } }
            catch (error: Exception) {
                synchronized(lock) { if (neighborListener?.id == id) neighborListener = null }
                throw error
            }
        }
        override fun closeNeighborMonitor(generation: Long) {
            val id = synchronized(lock) {
                if (generation != this.generation || closed.get()) return
                (neighborListener?.id ?: 0L).also { neighborListener = null }
            }
            try { unit(CLOSE_NEIGHBORS, generation) { it.writeLong(id) } } catch (_: IOException) { }
        }
        override fun features(generation: Long): Platform.Features = call(FEATURES, generation, read = ::readFeatures)
        override fun wifiState(generation: Long): Platform.WifiState = call(WIFI, generation) { Platform.WifiState(text(it), text(it)) }
        override fun clearDnsCache(generation: Long) = unit(CLEAR_CACHE, generation)
        override fun notification(generation: Long, notice: Platform.Notice) = unit(NOTICE, generation) {
            writeText(it, notice.id); it.writeInt(notice.type); writeText(it, notice.title); writeText(it, notice.body)
        }
        override fun cancelNotification(generation: Long, id: String, type: Int) = unit(CANCEL_NOTICE, generation) { writeText(it, id); it.writeInt(type) }
        override fun registerInterface(generation: Long, name: String) = unit(REGISTER, generation) { writeText(it, name) }
        override fun hostname(generation: Long): String = call(HOSTNAME, generation, read = { text(it) })
        override fun traffic(generation: Long, txTotal: Long, rxTotal: Long) {
            require(txTotal >= 0 && rxTotal >= 0) { "PLATFORM_TRAFFIC" }
            unit(TRAFFIC, generation) { it.writeLong(txTotal); it.writeLong(rxTotal) }
        }

        override fun close() = retire(true)

        private fun retire(release: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            val duplicate = synchronized(lock) {
                defaultListener = null; neighborListener = null
                tun.also { tun = null }
            }
            try { duplicate?.close() } catch (_: IOException) { }
            try { remote.unlinkToDeath(death, 0) } catch (_: Exception) { }
            if (release) try { unit(RELEASE, generation) } catch (_: Exception) { }
        }
    }

    private data class DefaultEvent(val id: Long, val value: Platform.DefaultInterface)
    private data class NeighborEvent(val id: Long, val values: List<Platform.Neighbor>)
    private data class DefaultRegistration(val id: Long, val listener: Platform.DefaultListener)
    private data class NeighborRegistration(val id: Long, val listener: Platform.NeighborListener)

    private fun int(parcel: Parcel): Int { require(parcel.dataAvail() >= 4) { "PLATFORM_TRUNCATED" }; return parcel.readInt() }
    private fun long(parcel: Parcel): Long { require(parcel.dataAvail() >= 8) { "PLATFORM_TRUNCATED" }; return parcel.readLong() }
    private fun end(parcel: Parcel) { require(parcel.dataAvail() == 0) { "PLATFORM_TRAILING_DATA" } }
    private fun bool(parcel: Parcel): Boolean = when (int(parcel)) { 0 -> false; 1 -> true; else -> throw IOException("PLATFORM_BOOLEAN") }
    private fun writeBool(parcel: Parcel, value: Boolean) = parcel.writeInt(if (value) 1 else 0)

    /** Inspect Parcel's byte-array prefix BEFORE its allocator/decoder sees it. */
    private fun bytes(parcel: Parcel, maximum: Int, minimum: Int = 0): ByteArray {
        val position = parcel.dataPosition()
        val length = int(parcel)
        require(length in minimum..maximum && ((length.toLong() + 3) and -4L) <= parcel.dataAvail()) { "PLATFORM_BYTE_SIZE" }
        parcel.setDataPosition(position)
        return parcel.createByteArray() ?: throw IOException("PLATFORM_BYTES")
    }

    private fun text(parcel: Parcel, maximum: Int = MAX_TEXT): String {
        val raw = bytes(parcel, maximum)
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
    }

    private fun writeBytes(parcel: Parcel, value: ByteArray, maximum: Int) {
        require(value.size <= maximum && parcel.dataSize().toLong() + value.size + 8 <= MAX_PARCEL) { "PLATFORM_BYTE_SIZE" }
        parcel.writeByteArray(value)
    }

    private fun writeText(parcel: Parcel, value: String, maximum: Int = MAX_TEXT) {
        require(value.length <= maximum) { "PLATFORM_TEXT_SIZE" }
        val buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        require(buffer.remaining() <= maximum) { "PLATFORM_TEXT_SIZE" }
        val raw = ByteArray(buffer.remaining()); buffer.get(raw)
        writeBytes(parcel, raw, maximum)
    }

    private fun <T> readList(parcel: Parcel, read: () -> T): List<T> {
        val size = int(parcel)
        require(size in 0..MAX_LIST && size <= parcel.dataAvail() / 4) { "PLATFORM_LIST_SIZE" }
        return List(size) { read() }
    }
    private fun <T> writeList(parcel: Parcel, values: List<T>, write: (T) -> Unit) {
        require(values.size <= MAX_LIST) { "PLATFORM_LIST_SIZE" }
        parcel.writeInt(values.size)
        values.forEach { write(it); require(parcel.dataSize() <= MAX_PARCEL) { "PLATFORM_PARCEL_SIZE" } }
    }
    private fun strings(parcel: Parcel) = readList(parcel) { text(parcel) }
    private fun writeStrings(parcel: Parcel, values: List<String>) = writeList(parcel, values) { writeText(parcel, it) }
    private fun writeDefault(parcel: Parcel, value: Platform.DefaultInterface) {
        writeText(parcel, value.name); parcel.writeInt(value.index); writeBool(parcel, value.metered); writeBool(parcel, value.expensive)
    }
    private fun readDefault(parcel: Parcel) = Platform.DefaultInterface(text(parcel), int(parcel), bool(parcel), bool(parcel))
    private fun writeFeatures(parcel: Parcel, value: Platform.Features) {
        writeBool(parcel, value.autoDetectInterfaceControl); writeBool(parcel, value.procFs)
        writeBool(parcel, value.networkExtension); writeBool(parcel, value.includeAllNetworks)
        listOf(value.wifi, value.cache, value.notifications, value.neighbors, value.shell, value.bridge).forEach { parcel.writeInt(it.ordinal) }
    }
    private fun readFeatures(parcel: Parcel): Platform.Features {
        fun availability(): Platform.Availability {
            val value = int(parcel)
            return Platform.Availability.values().getOrNull(value) ?: throw IOException("PLATFORM_AVAILABILITY")
        }
        return Platform.Features(bool(parcel), bool(parcel), bool(parcel), bool(parcel),
            availability(), availability(), availability(), availability(), availability(), availability())
    }
}
