package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.mcrf.engine.protocol.v1.OwnedPacketBackend
import org.mcrf.engine.protocol.v1.PacketBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OwnedPacketBackendTest {
    private val request = PacketBackend.Request(8, 42, 1280, "secret-uapi")
    private class Host : PacketBackend.Host {
        @Volatile var stopped = false
        var prepared = 0
        var allowed = true
        override fun stopping(generation: Long) = stopped || generation != 8L
        override fun prepareSocket(generation: Long, borrowedSocketFd: Int): Boolean {
            prepared++; return allowed && generation == 8L && borrowedSocketFd == 12
        }
    }

    @Test fun ownsOnlyNativeSessionAndStartsOnce() {
        val closes = AtomicInteger()
        val host = Host()
        val backend = OwnedPacketBackend { fd, mtu, config, prepare ->
            assertEquals(42, fd); assertEquals(1280, mtu); assertEquals("secret-uapi", config)
            assertTrue(prepare.prepare(12))
            OwnedPacketBackend.NativeSession { closes.incrementAndGet() }
        }
        backend.start(request, host)
        assertEquals(1, host.prepared)
        assertThrows(IllegalStateException::class.java) { backend.start(request, host) }
        backend.close(); backend.close()
        assertEquals(1, closes.get())
        assertFalse(request.toString().contains("secret-uapi"))
    }

    @Test fun stopDuringNativeStartClosesLateSessionWithoutDeadlock() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val closed = AtomicInteger(); val failure = AtomicReference<Throwable>()
        val backend = OwnedPacketBackend { _, _, _, protector ->
            entered.countDown()
            check(finish.await(3, TimeUnit.SECONDS))
            assertFalse(protector.prepare(12))
            OwnedPacketBackend.NativeSession { closed.incrementAndGet() }
        }
        val thread = Thread { try { backend.start(request, Host()) } catch (e: Throwable) { failure.set(e) } }
        thread.start()
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); backend.close() }
        finally { finish.countDown(); thread.join(4000) }
        assertFalse(thread.isAlive)
        assertEquals(1, closed.get())
        assertEquals("PACKET_BACKEND_START_FAILED", failure.get()?.message)
    }

    @Test fun protectionFailureStaleGenerationAndStopRefuseUse() {
        val host = Host().apply { allowed = false }
        var callback: OwnedPacketBackend.SocketPreparation? = null
        val backend = OwnedPacketBackend { _, _, _, protector ->
            callback = protector
            assertFalse(protector.prepare(12))
            assertFalse(protector.prepare(-1))
            throw IllegalArgumentException("secret-uapi")
        }
        val error = assertThrows(IllegalStateException::class.java) { backend.start(request, host) }
        assertEquals("PACKET_BACKEND_START_FAILED", error.message)
        assertNull(error.cause)
        backend.close()
        assertFalse(callback!!.prepare(12))
        var starts = 0
        val stale = OwnedPacketBackend { _, _, _, _ -> starts++; null }
        assertThrows(IllegalStateException::class.java) { stale.start(request, Host().apply { stopped = true }) }
        assertEquals(0, starts)
    }

    @Test fun closeBeforeStartNullSessionAndCloseErrorsAreBounded() {
        val backend = OwnedPacketBackend { _, _, _, _ -> null }
        backend.close()
        assertThrows(IllegalStateException::class.java) { backend.start(request, Host()) }
        val nullBackend = OwnedPacketBackend { _, _, _, _ -> null }
        assertThrows(IllegalStateException::class.java) { nullBackend.start(request, Host()) }
        val badClose = OwnedPacketBackend { _, _, _, _ -> OwnedPacketBackend.NativeSession { error("secret-uapi") } }
        badClose.start(request, Host())
        val error = assertThrows(IllegalStateException::class.java) { badClose.close() }
        assertEquals("PACKET_BACKEND_CLOSE_FAILED", error.message); assertNull(error.cause)
        assertThrows(IllegalStateException::class.java) { badClose.close() }
    }

    @Test fun failedCloseRetainsExactSessionUntilARealSuccessfulRetry() {
        val closes=AtomicInteger();var protector:OwnedPacketBackend.SocketPreparation?=null
        val backend=OwnedPacketBackend {_,_,_,prepare->
            protector=prepare
            OwnedPacketBackend.NativeSession {if(closes.incrementAndGet()==1)error("secret-uapi")}
        }
        backend.start(request,Host())
        val failure=assertThrows(IllegalStateException::class.java){backend.close()}
        assertEquals("PACKET_BACKEND_CLOSE_FAILED",failure.message);assertNull(failure.cause)
        assertFalse(protector!!.prepare(12))
        backend.close();backend.close();assertEquals(2,closes.get())
        assertThrows(IllegalStateException::class.java){backend.start(request,Host())}
    }

    @Test fun lateSessionCloseFailureCanBeRetriedAfterStartWasCancelled() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val closes=AtomicInteger()
        val failure=AtomicReference<Throwable?>()
        val backend=OwnedPacketBackend {_,_,_,_->
            entered.countDown();check(release.await(3,TimeUnit.SECONDS))
            OwnedPacketBackend.NativeSession {if(closes.incrementAndGet()==1)error("secret-uapi")}
        }
        val thread=Thread {try{backend.start(request,Host())}catch(error:Throwable){failure.set(error)}}
        thread.start()
        try {assertTrue(entered.await(3,TimeUnit.SECONDS));backend.close()}
        finally {release.countDown();thread.join(4000)}
        assertFalse(thread.isAlive);assertEquals("PACKET_BACKEND_CLOSE_FAILED",failure.get()?.message)
        backend.close();backend.close();assertEquals(2,closes.get())
    }

    @Test fun concurrentCloseCallsDoNotCloseTheSameSessionInParallel() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val closes=AtomicInteger()
        val failure=AtomicReference<Throwable?>()
        val backend=OwnedPacketBackend {_,_,_,_->OwnedPacketBackend.NativeSession {
            closes.incrementAndGet();entered.countDown();check(release.await(3,TimeUnit.SECONDS))
        }}
        backend.start(request,Host())
        fun closing()=Thread {try{backend.close()}catch(error:Throwable){failure.compareAndSet(null,error)}}
        val first=closing();val second=closing();first.start()
        try {assertTrue(entered.await(3,TimeUnit.SECONDS));second.start()}
        finally {release.countDown();first.join(4000);if(second.state!=Thread.State.NEW)second.join(4000)}
        assertFalse(first.isAlive);assertFalse(second.isAlive);assertNull(failure.get());assertEquals(1,closes.get())
    }

    @Test fun requestRejectsInvalidFdMtuGenerationAndConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { PacketBackend.Request(0, 42, 1280, "x") }
        assertThrows(IllegalArgumentException::class.java) { PacketBackend.Request(1, -1, 1280, "x") }
        assertThrows(IllegalArgumentException::class.java) { PacketBackend.Request(1, 42, 576, "x") }
        assertThrows(IllegalArgumentException::class.java) { PacketBackend.Request(1, 42, 1280, "x\u0000y") }
        assertThrows(IllegalArgumentException::class.java) { PacketBackend.Request(1, 42, 1280, "я".repeat(40000)) }
    }

    @Test fun nativeTunRejectionUsesOnlyClosedSecretFreeType() {
        val backend = OwnedPacketBackend { _, _, _, _ -> throw PacketBackend.InvalidTun() }
        val rejection = assertThrows(PacketBackend.InvalidTun::class.java) { backend.start(request, Host()) }
        assertEquals("PACKET_NATIVE_TUN_REJECTED", rejection.message)
        assertNull(rejection.cause)
        assertTrue(rejection.stackTrace.isEmpty())
        backend.close()
    }
}
