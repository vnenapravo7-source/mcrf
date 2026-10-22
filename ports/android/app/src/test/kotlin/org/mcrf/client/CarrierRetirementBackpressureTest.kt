package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Queued for the WHOLE P2 common verification, not a per-edit run. */
class CarrierRetirementBackpressureTest {
    @Test fun fullPipeCannotPreventGraceOrForcedRetirementAndWriterIsJoined() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val pipe=object:OutputStream(){override fun write(value:Int){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        val child=object:Process(){
            @Volatile var alive=true
            val waits=mutableListOf<Long>()
            override fun getOutputStream()=pipe
            override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
            override fun isAlive()=alive
            override fun exitValue():Int{check(!alive);return 0}
            override fun waitFor()=error("unbounded wait forbidden")
            override fun waitFor(timeout:Long,unit:TimeUnit):Boolean{waits.add(unit.toSeconds(timeout));return !alive}
            override fun destroy(){}
            override fun destroyForcibly():Process{alive=false;return this}
        }
        val ipc=CarrierIpcWriter(pipe)
        assertTrue(ipc.offer("CAPTCHA_RESULT|exact-child-only\n".toByteArray()))
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val retirement=ClientCarrierRetirement(child,22,ipc)
        val returned=AtomicBoolean(false)
        val owner=Thread{retirement.close();returned.set(true)}
        owner.start()
        try {
            // Wait for exact child death, NOT for the blocked stdin writer.
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(child.alive&&System.nanoTime()<deadline)Thread.yield()
            assertFalse(child.alive)
            assertEquals(listOf(22L,1L,3L),child.waits)
            assertFalse(returned.get());assertFalse(retirement.complete)
            assertFalse(ipc.offer("LATE\n".toByteArray()))
        }finally{release.countDown();owner.join(5000)}
        assertFalse(owner.isAlive);assertTrue(returned.get())
        assertTrue(retirement.complete);assertTrue(retirement.forced)
        assertFalse(retirement.normalExit)
        assertTrue(ipc.awaitCompletion(0).writerExited)
    }
}
