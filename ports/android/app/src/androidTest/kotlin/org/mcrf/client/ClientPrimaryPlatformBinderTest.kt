package org.mcrf.client

import android.os.IBinder
import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.system.Os
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.mcrf.engine.spi.v1.Platform

/**
 * Device-only checks using real Android Binder, Parcel and kernel descriptors. Invoke run
 * from the device instrumentation owner. No SDK-stub JVM execution is valid for this suite.
 * These exercise local Binder transport, not cross-process UID enforcement, actual VPN
 * protection, Android Builder policy or the system resolver.
 */
object ClientPrimaryPlatformBinderTest {
    private const val GENERATION = 47L
    private const val DESCRIPTOR = "org.mcrf.client.PrimaryHostPlatform.v1"

    @JvmStatic fun run(passed: Consumer<String>) {
        descriptorsAndDtos()
        passed.accept("Platform Binder transfers actual socket/TUN duplicates and bounded DTOs; proxy cleanup preserves host descriptors")
        rejectsBeforeBackend()
        passed.accept("Platform Binder rejects oversized parcels, hostile lengths and stale generations before backend calls")
        monitorSubscriptions()
        passed.accept("Platform Binder filters closed/replaced monitor subscriptions and stale callback generations")
        monitorCloseBeforeStart()
        passed.accept("Platform Binder remembers monitor close-before-start ordering without reviving the closed subscription")
        lateTunAfterClose()
        passed.accept("Platform Binder rejects a late TUN result after proxy retirement")
    }

    private class Fixture {
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        val monitor = AtomicReference<Platform.DefaultListener?>()
        var open: () -> Int = { error("Unexpected TUN") }
        var prepare: (Int) -> Boolean = { error("Unexpected socket") }
        val platform = Proxy.newProxyInstance(Platform::class.java.classLoader,
            arrayOf(Platform::class.java, AutoCloseable::class.java)) { _, method, args ->
            if (method.name == "close") { closes.incrementAndGet(); return@newProxyInstance null }
            check((args?.get(0) as? Long) == GENERATION)
            calls.incrementAndGet()
            when (method.name) {
                "openTun" -> open()
                "protectAndBind" -> prepare(args!![1] as Int)
                "features" -> Platform.Features(true, false, false, false,
                    Platform.Availability.EMPTY_COMPATIBILITY, Platform.Availability.EMPTY_COMPATIBILITY,
                    Platform.Availability.EMPTY_COMPATIBILITY, Platform.Availability.EMPTY_COMPATIBILITY,
                    Platform.Availability.UNSUPPORTED, Platform.Availability.UNSUPPORTED)
                "interfaces" -> listOf(Platform.InterfaceInfo("fixture0", 9, 1500, 65, "wifi", false,
                    listOf("192.0.2.1/24", "2001:db8::1/64"), listOf("192.0.2.53")))
                "connectionOwner" -> Platform.ConnectionOwner(12345, listOf("org.mcrf.fixture"))
                "exchangeDns" -> Platform.DnsReply((args!![1] as Platform.DnsQuery).packet(), 0, 0)
                "wifiState" -> Platform.WifiState("", "")
                "hostname" -> "mcrf-android"
                "startDefaultMonitor" -> { monitor.set(args!![1] as Platform.DefaultListener); null }
                "closeDefaultMonitor" -> { monitor.set(null); null }
                else -> null
            }
        } as Platform
    }

    private fun plan(): Platform.TunPlan {
        val empty = emptyList<String>()
        return Platform.TunPlan(1500, true, true, false,
            Platform.Routes(listOf("192.0.2.1/30"), empty, listOf("0.0.0.0/0"), empty, empty, empty, empty, empty),
            listOf("192.0.2.2"), empty, empty, Platform.HttpProxy(false, "", 0, empty))
    }

    private fun descriptorsAndDtos() {
        val pipe = ParcelFileDescriptor.createPipe()
        val sockets = ParcelFileDescriptor.createSocketPair()
        val fixture = Fixture()
        var hostSocketFd = -1
        fixture.open = { pipe[0].fd }
        val originalSocket = Os.fstat(sockets[0].fileDescriptor)
        fixture.prepare = { fd ->
            hostSocketFd = fd
            check(fd != sockets[0].fd)
            ParcelFileDescriptor.fromFd(fd).use {
                val actual = Os.fstat(it.fileDescriptor)
                actual.st_dev == originalSocket.st_dev && actual.st_ino == originalSocket.st_ino
            }
        }
        val platform = ClientPrimaryPlatformBinder.proxy(ClientPrimaryPlatformBinder.stub(fixture.platform, GENERATION), GENERATION)
        try {
            check(platform.protectAndBind(GENERATION, sockets[0].fd))
            check(hostSocketFd >= 0)
            check(runCatching { ParcelFileDescriptor.fromFd(hostSocketFd).close() }.isFailure)
            Os.fstat(sockets[0].fileDescriptor) // The original worker socket is still owned by its caller.
            val borrowed = platform.openTun(GENERATION, plan())
            check(borrowed != pipe[0].fd)
            Os.write(pipe[1].fileDescriptor, byteArrayOf(17, 23), 0, 2)
            ParcelFileDescriptor.fromFd(borrowed).use {
                val buffer = ByteArray(2)
                check(Os.read(it.fileDescriptor, buffer, 0, 2) == 2 && buffer.contentEquals(byteArrayOf(17, 23)))
            }
            check(runCatching { platform.openTun(GENERATION, plan()) }.isFailure)
            check(platform.interfaces(GENERATION).single().addresses == listOf("192.0.2.1/24", "2001:db8::1/64"))
            check(platform.connectionOwner(GENERATION, Platform.ConnectionQuery(6, "192.0.2.1", 123, "192.0.2.2", 443)).uid == 12345)
            val packet = ByteArray(12).also { it[0] = 42 }
            check(platform.exchangeDns(GENERATION, Platform.DnsQuery(1, packet, 8000)).packet().contentEquals(packet))
            val features = platform.features(GENERATION)
            check(features.autoDetectInterfaceControl && !features.procFs && !features.networkExtension && !features.includeAllNetworks)
            check(features.wifi == Platform.Availability.EMPTY_COMPATIBILITY && features.shell == Platform.Availability.UNSUPPORTED)
            check(features.bridge == Platform.Availability.UNSUPPORTED && platform.wifiState(GENERATION).ssid.isEmpty())
            check(platform.hostname(GENERATION) == "mcrf-android")
            platform.clearDnsCache(GENERATION)
            platform.notification(GENERATION, Platform.Notice("fixture", 1, "title", "body"))
            platform.cancelNotification(GENERATION, "fixture", 1)
            platform.registerInterface(GENERATION, "fixture0")
            platform.traffic(GENERATION, 123, 456)
            ClientPrimaryPlatformBinder.closeProxy(platform)
            ClientPrimaryPlatformBinder.closeProxy(platform)
            check(fixture.closes.get() == 1)
            check(runCatching { ParcelFileDescriptor.fromFd(borrowed).close() }.isFailure)
            Os.fstat(pipe[0].fileDescriptor) // Main retains the original TUN until its own cleanup.
            check(!platform.protectAndBind(GENERATION, sockets[0].fd))
        } finally {
            ClientPrimaryPlatformBinder.closeProxy(platform)
            (pipe.toList() + sockets.toList()).forEach { it.close() }
        }
    }

    private fun rejectsBeforeBackend() {
        val fixture = Fixture()
        val binder = ClientPrimaryPlatformBinder.stub(fixture.platform, GENERATION)
        val platform = ClientPrimaryPlatformBinder.proxy(binder, GENERATION)
        fun reject(code: Int, write: (Parcel) -> Unit) {
            val input = Parcel.obtain()
            val output = Parcel.obtain()
            val before = fixture.calls.get()
            try {
                write(input)
                val denied = try {
                    check(binder.transact(code, input, output, 0))
                    output.setDataPosition(0)
                    output.readInt() != 0
                } catch (_: Exception) { true }
                check(denied && fixture.calls.get() == before)
            } finally { input.recycle(); output.recycle() }
        }
        try {
            reject(IBinder.FIRST_CALL_TRANSACTION + 18) {
                it.writeInterfaceToken(DESCRIPTOR); it.writeLong(GENERATION + 1); it.writeLong(1); it.writeLong(2)
            }
            reject(IBinder.FIRST_CALL_TRANSACTION + 1) { it.writeByteArray(ByteArray(289 * 1024)) }
            reject(IBinder.FIRST_CALL_TRANSACTION + 3) {
                it.writeInterfaceToken(DESCRIPTOR); it.writeLong(GENERATION); it.writeInt(6)
                it.writeInt(Int.MAX_VALUE) // Forged UTF-8 array length, no backing bytes.
            }
            reject(IBinder.FIRST_CALL_TRANSACTION + 4) {
                it.writeInterfaceToken(DESCRIPTOR); it.writeLong(GENERATION); it.writeLong(1); it.writeInt(8000)
                it.writeInt(65536)
            }
        } finally { ClientPrimaryPlatformBinder.closeProxy(platform) }
    }

    private fun monitorSubscriptions() {
        val fixture = Fixture()
        val platform = ClientPrimaryPlatformBinder.proxy(ClientPrimaryPlatformBinder.stub(fixture.platform, GENERATION), GENERATION)
        val delivered = AtomicInteger()
        try {
            val first = CountDownLatch(1)
            platform.startDefaultMonitor(GENERATION) { generation, value ->
                check(generation == GENERATION && value.name == "first"); delivered.incrementAndGet(); first.countDown()
            }
            val old = fixture.monitor.get()!!
            old.update(GENERATION + 1, Platform.DefaultInterface("stale", 1, false, false))
            old.update(GENERATION, Platform.DefaultInterface("first", 1, false, false))
            check(first.await(2, TimeUnit.SECONDS))
            platform.closeDefaultMonitor(GENERATION)
            old.update(GENERATION, Platform.DefaultInterface("closed", 1, false, false))
            val second = CountDownLatch(1)
            platform.startDefaultMonitor(GENERATION) { _, value ->
                check(value.name == "second"); delivered.incrementAndGet(); second.countDown()
            }
            old.update(GENERATION, Platform.DefaultInterface("replaced", 1, false, false))
            fixture.monitor.get()!!.update(GENERATION, Platform.DefaultInterface("second", 2, true, false))
            check(second.await(2, TimeUnit.SECONDS) && delivered.get() == 2)
        } finally { ClientPrimaryPlatformBinder.closeProxy(platform) }
    }

    private fun lateTunAfterClose() {
        val pipe = ParcelFileDescriptor.createPipe()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture()
        fixture.open = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); pipe[0].fd }
        val platform = ClientPrimaryPlatformBinder.proxy(ClientPrimaryPlatformBinder.stub(fixture.platform, GENERATION), GENERATION)
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<Boolean> { runCatching { platform.openTun(GENERATION, plan()) }.isFailure }
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            ClientPrimaryPlatformBinder.closeProxy(platform)
            release.countDown()
            check(result.get(2, TimeUnit.SECONDS))
            Os.fstat(pipe[0].fileDescriptor)
        } finally {
            release.countDown(); executor.shutdownNow()
            ClientPrimaryPlatformBinder.closeProxy(platform)
            pipe.forEach { it.close() }
        }
    }

    private fun monitorCloseBeforeStart() {
        val fixture = Fixture()
        val endpoint = ClientPrimaryPlatformBinder.stub(fixture.platform, GENERATION)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starts = AtomicInteger()
        val delayed = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.FIRST_CALL_TRANSACTION + 6 && starts.incrementAndGet() == 1) {
                    entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
                }
                return endpoint.transact(code, data, reply, flags)
            }
        }
        val platform = ClientPrimaryPlatformBinder.proxy(delayed, GENERATION)
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<Boolean> {
            runCatching { platform.startDefaultMonitor(GENERATION) { _, _ -> error("Closed callback") } }.isFailure
        }
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            platform.closeDefaultMonitor(GENERATION)
            release.countDown()
            check(result.get(2, TimeUnit.SECONDS))
            check(fixture.monitor.get() == null)
            platform.startDefaultMonitor(GENERATION) { _, _ -> }
            check(fixture.monitor.get() != null)
        } finally {
            release.countDown(); executor.shutdownNow()
            ClientPrimaryPlatformBinder.closeProxy(platform)
        }
    }
}
