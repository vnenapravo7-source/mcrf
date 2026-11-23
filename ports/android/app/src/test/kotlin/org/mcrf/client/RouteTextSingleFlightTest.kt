package org.mcrf.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RouteTextSingleFlightTest {
    private fun result(text:String)=mapOf("domain" to listOf(text))
    private fun <T> task(action:()->T):Pair<Thread,FutureTask<T>> {
        val future=FutureTask<T> {action()}
        val thread=Thread(future).apply {isDaemon=true;start()}
        return thread to future
    }
    private fun <T> Pair<Thread,FutureTask<T>>.value():T=second.get(4,TimeUnit.SECONDS)
    private fun waiting(thread:Thread) {
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(700)
        while(thread.state!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline)Thread.yield()
        assertEquals(Thread.State.TIMED_WAITING,thread.state)
    }
    private fun usage(cache:RouteTextCache.FixedParser):Pair<Int,Long> {
        fun field(name:String)=cache.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(cache)
        return synchronized(field("lock")) {
            (field("pending") as Map<*,*>).size to (field("pendingBytes") as Long)
        }
    }

    @Test fun overlappingSameExactTextCompilesOnceAndPublishesImmutableSnapshot() {
        val calls=AtomicInteger();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val mutable=mutableListOf("safe")
        val cache=RouteTextCache.singleFlight {
            calls.incrementAndGet();entered.countDown();check(release.await(3,TimeUnit.SECONDS))
            mapOf("domain" to mutable)
        }
        val owner=task {cache.parse("same")};assertTrue(entered.await(1,TimeUnit.SECONDS))
        val peers=List(3){task {cache.parse("same")}}
        try {peers.forEach {waiting(it.first)}} finally {release.countDown()}
        val first=owner.value();peers.forEach {assertSame(first,it.value())}
        assertEquals(1,calls.get());assertEquals(0 to 0L,usage(cache))
        mutable.add("changed");assertEquals(listOf("safe"),first["domain"])
        assertThrows(UnsupportedOperationException::class.java){(first as MutableMap)["bad"]=listOf("x")}
        assertThrows(UnsupportedOperationException::class.java){(first.getValue("domain") as MutableList).add("x")}
    }

    @Test fun differentTextsCompileConcurrentlyAndNeverShareResults() {
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        lateinit var cache:RouteTextCache.FixedParser
        cache=RouteTextCache.singleFlight {text->
            val lock=cache.javaClass.getDeclaredField("lock").apply {isAccessible=true}.get(cache)
            val results=cache.javaClass.getDeclaredField("cache").apply {isAccessible=true}.get(cache)
            check(!Thread.holdsLock(lock)&&!Thread.holdsLock(results))
            entered.countDown();check(release.await(3,TimeUnit.SECONDS));result(text)
        }
        val a=task {cache.parse("first")};val b=task {cache.parse("second")}
        try {assertTrue(entered.await(1,TimeUnit.SECONDS))}finally {release.countDown()}
        assertEquals(listOf("first"),a.value()["domain"]);assertEquals(listOf("second"),b.value()["domain"])
        assertEquals(listOf("first"),cache.parse("first")["domain"])
    }

    @Test fun arbitraryCallbackApiStillRunsBothOverlappingCallbacks() {
        val cache=RouteTextCache();val entered=CountDownLatch(2);val release=CountDownLatch(1)
        val a=task {cache.parse("same"){entered.countDown();check(release.await(3,TimeUnit.SECONDS));result("a")}}
        val b=task {cache.parse("same"){entered.countDown();check(release.await(3,TimeUnit.SECONDS));result("b")}}
        try {assertTrue(entered.await(1,TimeUnit.SECONDS))}finally {release.countDown()}
        a.value();b.value() // Legacy cache publication rules remain authoritative.
    }

    @Test fun ownerFailureReleasesWaiterAndDoesNotCacheTheFailure() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val cache=RouteTextCache.singleFlight {text->
            val call=calls.incrementAndGet()
            if(call==1){entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
            check(call>2){"fixture failure"};result(text)
        }
        val owner=task {cache.parse("same")};assertTrue(entered.await(1,TimeUnit.SECONDS))
        val peer=task {cache.parse("same")}
        try {waiting(peer.first)}finally {release.countDown()}
        for(task in listOf(owner,peer)) {
            val failure=assertThrows(ExecutionException::class.java){task.value()}
            assertTrue(failure.cause is IllegalStateException)
        }
        assertEquals(0 to 0L,usage(cache))
        assertEquals(result("same"),cache.parse("same"));cache.parse("same");assertEquals(3,calls.get())
    }

    @Test fun ownerReentryOnSameTextDoesNotWaitOnItself() {
        val calls=AtomicInteger();lateinit var cache:RouteTextCache.FixedParser
        cache=RouteTextCache.singleFlight {text->if(calls.incrementAndGet()==1)cache.parse(text)else result(text)}
        assertEquals(result("same"),task {cache.parse("same")}.value())
        assertEquals(2,calls.get());assertEquals(0 to 0L,usage(cache))
    }

    @Test fun ownerErrorStillReleasesWaiterAndCannotBecomeACachedFailure() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val cache=RouteTextCache.singleFlight {text->
            if(calls.incrementAndGet()==1) {
                entered.countDown();check(release.await(3,TimeUnit.SECONDS));throw AssertionError("fixture owner error")
            }
            result(text)
        }
        val owner=task {cache.parse("same")};assertTrue(entered.await(1,TimeUnit.SECONDS))
        val peer=task {cache.parse("same")}
        try {waiting(peer.first)}finally {release.countDown()}
        assertTrue(assertThrows(ExecutionException::class.java){owner.value()}.cause is AssertionError)
        assertEquals(result("same"),peer.value());assertEquals(0 to 0L,usage(cache))
        cache.parse("same");cache.parse("same");assertEquals(3,calls.get())
    }

    @Test fun interruptedOwnerSharesValidResultButDoesNotRetainIt() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val cache=RouteTextCache.singleFlight {text->
            if(calls.incrementAndGet()==1) {
                entered.countDown();check(release.await(3,TimeUnit.SECONDS));Thread.currentThread().interrupt()
            }
            result(text)
        }
        val owner=task {cache.parse("same")};assertTrue(entered.await(1,TimeUnit.SECONDS))
        val peer=task {cache.parse("same")}
        try {waiting(peer.first)}finally {release.countDown()}
        val first=owner.value();assertSame(first,peer.value());assertEquals(0 to 0L,usage(cache))
        val next=cache.parse("same");assertNotSame(first,next);assertSame(next,cache.parse("same"))
        assertEquals(2,calls.get())
    }

    @Test fun ownersRecursingAcrossDifferentPendingTextsCannotFormAWaitCycle() {
        val barrier=CyclicBarrier(2);val calls=ConcurrentHashMap<String,AtomicInteger>()
        lateinit var cache:RouteTextCache.FixedParser
        cache=RouteTextCache.singleFlight {text->
            if(calls.computeIfAbsent(text){AtomicInteger()}.incrementAndGet()==1) {
                barrier.await(2,TimeUnit.SECONDS);cache.parse(if(text=="a")"b"else"a")
            }
            result(text)
        }
        val a=task {cache.parse("a")};val b=task {cache.parse("b")}
        assertEquals(result("a"),a.value());assertEquals(result("b"),b.value())
        assertEquals(0 to 0L,usage(cache))
    }

    @Test fun interruptedWaiterRestoresFlagAndFallsBackWithoutPublishing() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger();val interrupted=AtomicBoolean()
        val cache=RouteTextCache.singleFlight {text->
            if(calls.incrementAndGet()==1){entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
            else interrupted.set(Thread.currentThread().isInterrupted)
            result(text)
        }
        val owner=task {cache.parse("same")};assertTrue(entered.await(1,TimeUnit.SECONDS))
        val peer=task {cache.parse("same")}
        val fallback=try {waiting(peer.first);peer.first.interrupt();peer.value()}finally {release.countDown()}
        val first=owner.value()
        assertTrue(interrupted.get());assertEquals(2,calls.get())
        assertNotSame(first,fallback);assertSame(first,cache.parse("same"))
        assertEquals(0 to 0L,usage(cache))
    }

    @Test fun timeoutFallsBackWithoutReplacingStillRunningOwner() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        val cache=RouteTextCache.singleFlight {text->
            if(calls.incrementAndGet()==1){entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
            result(text)
        }
        val owner=task {cache.parse("same")};assertTrue(entered.await(1,TimeUnit.SECONDS))
        val fallback=try {task {cache.parse("same")}.value()}finally {release.countDown()}
        val first=owner.value()
        assertEquals(2,calls.get());assertNotSame(first,fallback);assertSame(first,cache.parse("same"))
    }

    @Test fun pendingCountAndRetainedTextBytesStayWithinConfiguredBounds() {
        val release=CountDownLatch(1);val entered=CountDownLatch(2)
        val cache=RouteTextCache.singleFlight(budget=600,maxEntries=1){text->entered.countDown();check(release.await(3,TimeUnit.SECONDS));result(text)}
        val a=task {cache.parse("a")};val b=task {cache.parse("b")}
        try {assertTrue(entered.await(1,TimeUnit.SECONDS));val usage=usage(cache);assertEquals(1,usage.first);assertTrue(usage.second in 1..600)}
        finally {release.countDown()}
        a.value();b.value();assertEquals(0 to 0L,usage(cache))
        val big="x".repeat(400);val started=CountDownLatch(2);val unblock=CountDownLatch(1)
        val oversized=RouteTextCache.singleFlight(budget=600){text->started.countDown();check(unblock.await(3,TimeUnit.SECONDS));result(text)}
        val c=task {oversized.parse(big)};val d=task {oversized.parse(big)}
        try {assertTrue(started.await(1,TimeUnit.SECONDS));assertEquals(0 to 0L,usage(oversized))}finally {unblock.countDown()}
        c.value();d.value()
    }

    @Test fun fixedParserKeepsEntryBudgetAndLruEviction() {
        val calls=AtomicInteger();val cache=RouteTextCache.singleFlight(maxEntries=1){text->calls.incrementAndGet();result(text)}
        for(text in listOf("a","b","a"))assertEquals(result(text),cache.parse(text))
        assertEquals(3,calls.get())
        val oversize=RouteTextCache.singleFlight(budget=1){text->calls.incrementAndGet();result(text)}
        repeat(2){oversize.parse("a")};assertEquals(5,calls.get());assertEquals(0 to 0L,usage(oversize))
    }
}
