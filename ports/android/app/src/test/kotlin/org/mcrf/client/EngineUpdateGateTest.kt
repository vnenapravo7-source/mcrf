package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

object EngineUpdateGateFixture {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args[0])
        val lease:AutoCloseable=when(args[1]){
            "usage"->requireNotNull(EngineUpdateGate.acquireUsage(root))
            "resume"->requireNotNull(EngineUpdateGate.resumeMutation(root,requireNotNull(EngineUpdateGate.pending(root)).token))
            else->requireNotNull(EngineUpdateGate.beginMutation(root,"fixture","apk","xray","a".repeat(64),17))
        }
        try{println("READY");System.out.flush();while(true)Thread.sleep(1000)}finally{lease.close()}
    }
}

class EngineUpdateGateTest {
    private fun fixture(action:(File)->Unit) {
        val root=Files.createTempDirectory("mcrf-engine-gate").toFile().canonicalFile
        try{action(root)}finally{root.listFiles().orEmpty().forEach{it.delete()};root.delete()}
    }
    private fun child(root:File,mode:String):Process {
        val java=File(System.getProperty("java.home"),"bin/java.exe").takeIf{it.isFile}?:File(System.getProperty("java.home"),"bin/java")
        val classes=mutableListOf(EngineUpdateGateFixture::class.java,EngineUpdateGate::class.java,kotlin.Unit::class.java,org.json.JSONObject::class.java)
        runCatching{Class.forName("android.content.Context",false,javaClass.classLoader)}.getOrNull()?.let{classes.add(it)}
        val classpath=classes.mapNotNull{it.protectionDomain?.codeSource?.location?.toURI()?.let(::File)?.path}.distinct().joinToString(File.pathSeparator)
        val child=ProcessBuilder(java.path,"-cp",classpath,EngineUpdateGateFixture::class.java.name,root.path,mode).redirectErrorStream(true).start()
        val ready=FutureTask{
            val reader=child.inputStream.bufferedReader()
            var line=reader.readLine();var skipped=0
            while(line!=null&&listOf("Picked up JAVA_TOOL_OPTIONS: ","Picked up _JAVA_OPTIONS: ","NOTE: Picked up JDK_JAVA_OPTIONS: ").any(line::startsWith)){
                require(++skipped<=3){"Too many JVM environment banners"};line=reader.readLine()
            }
            line
        };Thread(ready).apply{isDaemon=true;start()}
        try{assertEquals("READY",ready.get(10,TimeUnit.SECONDS))}catch(e:Throwable){
            child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);throw e
        }
        return child
    }
    private fun awaitMutation(root:File):EngineUpdateGate.Mutation {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<until){EngineUpdateGate.beginMutation(root,"fixture","apk","xray","a".repeat(64),17)?.let{return it};Thread.sleep(20)}
        throw AssertionError("Kernel ownership was not released after JVM termination")
    }
    private fun awaitPending(root:File):EngineUpdateGate.Pending {
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<until){try{EngineUpdateGate.pending(root)?.let{return it}}catch(_:IllegalStateException){};Thread.sleep(20)}
        throw AssertionError("Cannot read retained marker after JVM termination")
    }
    @Test fun sameProcessSharedLeaseRefcountsAndClosedHandleIsIdempotent()=fixture{root->
        val first=requireNotNull(EngineUpdateGate.acquireUsage(root));val second=requireNotNull(EngineUpdateGate.acquireUsage(root))
        assertNull(EngineUpdateGate.beginMutation(root,"fixture","apk","xray","a".repeat(64),17))
        first.close();first.close();assertNull(EngineUpdateGate.beginMutation(root,"fixture","apk","xray","a".repeat(64),17))
        second.close();val update=requireNotNull(EngineUpdateGate.beginMutation(root,"fixture","apk","xray","a".repeat(64),17))
        assertTrue(EngineUpdateGate.complete(root,update.pending.token){true});assertNotNull(EngineUpdateGate.acquireUsage(root)?.also{it.close()})
    }
    @Test fun runtimeInAnotherJvmPreventsMutationUntilActualLeaseRelease()=fixture{root->
        val child=child(root,"usage")
        try{assertNull(EngineUpdateGate.beginMutation(root,"fixture","apk","xray","a".repeat(64),17))
            val local=requireNotNull(EngineUpdateGate.acquireUsage(root));local.close()
        }finally{child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS))}
        val update=awaitMutation(root)
        EngineUpdateGate.complete(root,update.pending.token){true}
    }
    @Test fun killedInstallerJvmReleasesKernelLockButPendingMarkerStillBlocksStarts()=fixture{root->
        val child=child(root,"mutation")
        try{assertNull(EngineUpdateGate.acquireUsage(root))}finally{child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS))}
        val pending=awaitPending(root);assertNull(EngineUpdateGate.acquireUsage(root))
        File(root,"pending.json").setLastModified(1L);assertNull(EngineUpdateGate.acquireUsage(root))
        assertFalse(EngineUpdateGate.complete(root,pending.token){false}) // Failed exact receipt must retain barrier.
        assertNull(EngineUpdateGate.acquireUsage(root))
        assertTrue(EngineUpdateGate.complete(root,pending.token){true}) // Explicit host fixture completion, not Android receipt proof.
        assertNotNull(EngineUpdateGate.acquireUsage(root)?.also{it.close()})
    }
    @Test fun consumedInstallerRequestNeverTargetsANewerMutation()=fixture{root->
        val first=requireNotNull(EngineUpdateGate.beginMutation(root,"fixture","apk","xray","a".repeat(64),17));val old=first.pending
        EngineUpdateGate.complete(root,old.token){true}
        val next=requireNotNull(EngineUpdateGate.beginMutation(root,"fixture","apk","xray","b".repeat(64),18))
        assertNotEquals(old.requestCode,next.pending.requestCode)
        assertFalse(EngineUpdateGate.complete(root,old.token){true});assertNull(EngineUpdateGate.acquireUsage(root))
        EngineUpdateGate.complete(root,next.pending.token){true}
    }
    @Test fun uninstallRetainsBarrierAfterProcessLeaseAndUsesDistinctSystemResult()=fixture{root->
        val usage=requireNotNull(EngineUpdateGate.acquireUsage(root))
        assertNull(EngineUpdateGate.beginMutation(root,"engine_remove","remove","openflux"))
        usage.close()
        val removal=requireNotNull(EngineUpdateGate.beginMutation(root,"engine_remove","remove","openflux"))
        val pending=removal.pending
        assertTrue(EngineUpdateGate.isInstallerRequest(pending.requestCode))
        assertEquals("",pending.digest);assertEquals(0L,pending.bytes)
        removal.close()
        assertEquals(pending,EngineUpdateGate.pending(root))
        assertNull(EngineUpdateGate.acquireUsage(root))
        assertFalse(EngineUpdateGate.complete(root,pending.token){false})
        assertNull(EngineUpdateGate.acquireUsage(root))
        assertTrue(EngineUpdateGate.complete(root,pending.token){true})
        val install=requireNotNull(EngineUpdateGate.beginMutation(root,"fixture","apk","openflux","a".repeat(64),17))
        assertNotEquals(pending.requestCode,install.pending.requestCode)
        assertFalse(EngineUpdateGate.complete(root,pending.token){true})
        EngineUpdateGate.complete(root,install.pending.token){true}
    }
    @Test fun interruptedFluxRecoveryReacquiresOnlyItsTokenAndNeverOpensUsageGap()=fixture{root->
        val original=requireNotNull(EngineUpdateGate.beginMutation(root,"flux_candidate","flux","openflux"))
        val pending=original.pending;original.close()
        assertNull(EngineUpdateGate.acquireUsage(root))
        assertNull(EngineUpdateGate.beginMutation(root,"flux_candidate","flux","openflux"))
        assertNull(EngineUpdateGate.resumeMutation(root,java.util.UUID.randomUUID().toString()))
        assertEquals(pending,EngineUpdateGate.pending(root))
        val resumed=requireNotNull(EngineUpdateGate.resumeMutation(root,pending.token))
        assertEquals(pending,resumed.pending)
        assertNull(EngineUpdateGate.resumeMutation(root,pending.token))
        assertNull(EngineUpdateGate.acquireUsage(root))
        resumed.close() // A failed/blocked recovery must keep the original marker.
        assertEquals(pending,EngineUpdateGate.pending(root))
        assertNull(EngineUpdateGate.acquireUsage(root))
        val retry=requireNotNull(EngineUpdateGate.resumeMutation(root,pending.token))
        assertTrue(EngineUpdateGate.complete(root,retry.pending.token){true})
        assertNotNull(EngineUpdateGate.acquireUsage(root)?.also{it.close()})
    }
    @Test fun recoveryOwnerInAnotherJvmCannotBeBypassedAndCrashRetainsOriginalMarker()=fixture{root->
        val original=requireNotNull(EngineUpdateGate.beginMutation(root,"flux_candidate","flux","openflux"))
        val pending=original.pending;original.close()
        val child=child(root,"resume")
        try{
            assertNull(EngineUpdateGate.resumeMutation(root,pending.token))
            assertFalse(EngineUpdateGate.complete(root,pending.token){true})
            assertNull(EngineUpdateGate.acquireUsage(root))
        }finally{child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS))}
        assertEquals(pending,awaitPending(root))
        assertNull(EngineUpdateGate.acquireUsage(root))
        val retry=requireNotNull(EngineUpdateGate.resumeMutation(root,pending.token))
        EngineUpdateGate.complete(root,retry.pending.token){true}
    }
}
