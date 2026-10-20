package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class WarpControllerStateTest {
    @Test fun newJobInvalidatesOldResultAndProgress() {
        val state=WarpControllerState();val first=state.begin("previous");val second=state.begin(null)
        assertTrue(state.cancelled(first));assertFalse(state.current(first));assertFalse(state.cancelled(second))
        var committed=false
        assertThrows(CancellationException::class.java) {state.commit(first) {committed=true}}
        assertFalse(committed);assertEquals("saved",state.commit(second) {"saved"});assertFalse(state.running())
        assertThrows(CancellationException::class.java) {state.commit(second) {error("duplicate commit")}}
        assertFalse(state.finish(second))
    }
    @Test fun deletionPreventsResurrectionIncludingPendingRegistration() {
        val state=WarpControllerState();val ticket=state.begin(null);var deleted=false
        state.remove("existing") {deleted=true};assertTrue(deleted)
        assertThrows(CancellationException::class.java) {state.commit(ticket) {error("must not run")}}
        assertFalse(state.finish(ticket))
    }
    @Test fun detachPermanentlyInvalidatesCallbacks() {
        val state=WarpControllerState();val ticket=state.begin("old");state.close()
        assertThrows(CancellationException::class.java) {state.commit(ticket) {error("must not run")}}
        assertThrows(IllegalStateException::class.java) {state.begin(null)}
    }
    @Test fun commitAndDeleteCannotInterleave() {
        val state=WarpControllerState();val ticket=state.begin("old");val inside=CountDownLatch(1);val release=CountDownLatch(1)
        val worker=Executors.newFixedThreadPool(2);val events=java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            val commit=worker.submit {state.commit(ticket) {events.add("catalogue");inside.countDown();check(release.await(3,TimeUnit.SECONDS));events.add("selection")}}
            assertTrue(inside.await(3,TimeUnit.SECONDS))
            val delete=worker.submit {state.remove("old") {events.add("delete")}}
            assertThrows(TimeoutException::class.java) {delete.get(100,TimeUnit.MILLISECONDS)}
            release.countDown();commit.get(3,TimeUnit.SECONDS);delete.get(3,TimeUnit.SECONDS)
            assertEquals(listOf("catalogue","selection","delete"),events.toList())
        } finally {release.countDown();worker.shutdownNow()}
    }
}
