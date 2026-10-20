package org.mcrf.client

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real OS stdin pipes complement the latch tests; still NOT native child-tree acceptance. */
class CarrierIpcProcessTest {
    private class Peer(mode:String):AutoCloseable {
        val root=Files.createTempDirectory("mcrf-ipc-owned-")
        private val java=File(requireNotNull(System.getProperty("java.home")),"bin/"+if(System.getProperty("os.name").orEmpty().startsWith("Windows"))"java.exe"else"java")
        private val classes=File(requireNotNull(CarrierIpcPipeChild::class.java.protectionDomain).codeSource.location.toURI())
        // JVM environment-option notices go to stderr, not our framed protocol.
        // Keep inherited options intact; never parse or expose their contents.
        val process=ProcessBuilder(java.path,"-cp",classes.path,CarrierIpcPipeChild::class.java.name,mode,root.toString()).start()
        private val lines=LinkedBlockingQueue<String>()
        private val reader=Thread({process.inputStream.bufferedReader().use {input->while(true){val line=input.readLine()?:break;lines.offer(line)}}},"owned-ipc-test-output").apply {isDaemon=true;start()}
        private val errors=Thread({process.errorStream.use {input->val buffer=ByteArray(1024);try{while(input.read(buffer)>=0){buffer.fill(0)}}finally{buffer.fill(0)}}},"owned-ipc-test-stderr").apply {isDaemon=true;start()}
        fun line():String=lines.poll(5,TimeUnit.SECONDS)?:error("Owned child output deadline")
        fun release(name:String){require(name in setOf("read-enabled","exit-enabled"));Files.write(root.resolve(name),byteArrayOf(1))}
        fun exited(){assertTrue("Exact owned JVM child did not exit",process.waitFor(5,TimeUnit.SECONDS));assertEquals(0,process.exitValue())}
        override fun close(){
            // Only this invocation's Process handle and two exact owned markers.
            // Forced cleanup never serves as a successful graceful-exit assertion.
            if(process.isAlive){process.destroyForcibly();check(process.waitFor(5,TimeUnit.SECONDS))}
            reader.join(1000);errors.join(1000)
            for(name in listOf("read-enabled","exit-enabled"))Files.deleteIfExists(root.resolve(name))
            Files.delete(root)
        }
    }
    private fun hash(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it.toInt() and 255)}

    @Test fun actualBackpressuredPipePreservesFifoAndNonblockingStopAdmission() {
        Peer("blocked").use {peer->
            assertEquals("READY",peer.line())
            val writer=CarrierIpcWriter(peer.process.outputStream)
            val frames=(0 until 8).map {index->ByteArray(16384){(65+index).toByte()}.also {it[it.lastIndex]=10}}
            try {
                frames.forEach {assertTrue(writer.offer(it))}
                val started=System.nanoTime();writer.requestStop()
                assertTrue("STOP admission waited on actual stdin",System.nanoTime()-started<1_000_000_000L)
                val blocked=writer.awaitCompletion(0)
                assertFalse(blocked.writerExited);assertFalse(blocked.stopWritten);assertFalse(blocked.failed)
                assertFalse(writer.offer("LATE\n".toByteArray()))
                assertTrue(peer.process.isAlive)
                peer.release("read-enabled")
                assertEquals("STOP_SEEN 8 ${hash(frames.reduce {all,next->all+next})}",peer.line())
                val complete=writer.awaitCompletion(5000)
                assertTrue(complete.writerExited);assertTrue(complete.stopWritten);assertFalse(complete.failed)
                peer.exited()
            }finally{peer.release("read-enabled");writer.requestStop();writer.awaitCompletion(5000)}
        }
    }
    @Test fun realWriterExitAndStopAcknowledgmentAreNotChildDeath() {
        Peer("hold").use {peer->
            assertEquals("READY",peer.line())
            val writer=CarrierIpcWriter(peer.process.outputStream)
            try {
                writer.requestStop()
                assertEquals("STOP_SEEN 0 ${hash(byteArrayOf())}",peer.line())
                val complete=writer.awaitCompletion(5000)
                assertTrue(complete.writerExited);assertTrue(complete.stopWritten);assertFalse(complete.failed)
                assertTrue("Writer completion incorrectly used as child death",peer.process.isAlive)
                peer.release("exit-enabled");peer.exited()
            }finally{peer.release("exit-enabled");writer.requestStop();writer.awaitCompletion(5000)}
        }
    }
    @Test fun alreadyExitedExactChildCannotConfirmStopOrRetryProof() {
        Peer("exit").use {peer->
            assertEquals("READY",peer.line());peer.exited()
            val writer=CarrierIpcWriter(peer.process.outputStream)
            assertTrue(writer.offer("CAPTCHA_RESULT|owned-only\n".toByteArray()))
            writer.requestStop()
            val complete=writer.awaitCompletion(5000)
            assertTrue(complete.writerExited);assertTrue(complete.failed);assertFalse(complete.stopWritten)
            assertFalse(writer.offer("RETRY\n".toByteArray()))
        }
    }
}
