package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

class EngineUpdateQueueTest {
    private val bytes=ByteArray(8192){(it%251).toByte()}
    private val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun expected(hash:String=digest)=EnginePackage("arm64-v8a","fixture",URI("https://github.com/fixture"),bytes.size.toLong(),hash,"openflux")
    private fun fixture(action:(File,File,File)->Unit) {
        val root=Files.createTempDirectory("mcrf-engine-queue").toFile().canonicalFile
        val cache=File(root,"cache").apply{mkdir()};val queue=File(root,"noBackup").apply{mkdir()};val source=File(cache,"download.apk").apply{writeBytes(bytes)}
        try{action(source,cache,queue)}finally{
            cache.listFiles().orEmpty().forEach{it.delete()};queue.listFiles().orEmpty().forEach{it.delete()};cache.delete();queue.delete();root.delete()
        }
    }
    @Test fun durableQueueSurvivesCacheLossAndRecreatesVerifiedInstallerCopy()=fixture {source,cache,queue->
        val durable=EngineUpdateQueue.copyVerified(source,queue,expected())
        val installer=EngineUpdateQueue.copyVerified(durable,cache,expected())
        source.delete();installer.delete() // Simulated Android cache eviction, never device evidence.
        assertTrue(durable.isFile)
        assertTrue(expected().matchesInstalled(durable.length()){durable.inputStream()})
        val retried=EngineUpdateQueue.copyVerified(durable,cache,expected())
        assertTrue(expected().matchesInstalled(retried.length()){retried.inputStream()})
        assertEquals(durable,EngineUpdateQueue.resolve(queue,durable.name))
    }
    @Test fun hashFailureLeavesPreviousQueueAndSourceUntouched()=fixture {source,_,queue->
        val previous=File(queue,"previous-fixture.txt").apply{writeText("retain")}
        try{EngineUpdateQueue.copyVerified(source,queue,expected("0".repeat(64)));fail("bad digest accepted")}catch(_:IllegalArgumentException){}
        assertArrayEquals(bytes,source.readBytes());assertEquals("retain",previous.readText())
        assertEquals(listOf(previous.name),queue.listFiles().orEmpty().map{it.name})
    }
    @Test fun cancellationNeverCommitsPartialApk()=fixture {source,_,queue->
        try{EngineUpdateQueue.copyVerified(source,queue,expected()){true};fail("cancel accepted")}catch(_:IllegalStateException){}
        assertEquals(0,queue.listFiles().orEmpty().size);assertArrayEquals(bytes,source.readBytes())
    }
    @Test fun rejectsTraversalAndTruncationWithoutReplacingRegisteredData()=fixture {source,_,queue->
        try{EngineUpdateQueue.resolve(queue,"../foreign.apk");fail("traversal accepted")}catch(_:IllegalArgumentException){}
        source.writeBytes(bytes.copyOf(100))
        try{EngineUpdateQueue.copyVerified(source,queue,expected());fail("truncated accepted")}catch(_:IllegalArgumentException){}
        assertEquals(0,queue.listFiles().orEmpty().size)
    }
}
