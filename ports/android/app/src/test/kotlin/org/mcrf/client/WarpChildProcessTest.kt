package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WarpChildProcessTest {
    class Child {
        companion object {
            @JvmStatic fun main(args: Array<String>) {
                println("READY");System.out.flush()
                if(args.single()=="cooperate") System.`in`.bufferedReader().readLine()
                else Thread.sleep(60000)
            }
        }
    }
    private fun child(mode: String): Process {
        val binary=File(System.getProperty("java.home"),"bin/java.exe").takeIf { it.isFile }
            ?: File(System.getProperty("java.home"),"bin/java")
        val process=ProcessBuilder(binary.path,"-cp",System.getProperty("java.class.path"),Child::class.java.name,mode).start()
        try { assertEquals("READY",process.inputStream.bufferedReader().readLine());return process }
        catch(error: Throwable) {process.destroyForcibly();process.waitFor();throw error}
    }
    @Test fun gracefulStopExitsBeforeEnclosingUsageIsReleased() {
        val process=child("cooperate");var closed=false
        try {
            AutoCloseable {assertFalse(process.isAlive);closed=true}.use {WarpChildProcess.stop(process,graceMillis=1000)}
            assertTrue(closed);assertFalse(process.isAlive)
        } finally {if(process.isAlive){process.destroyForcibly();process.waitFor()}}
    }
    @Test fun interruptedCancellationForceStopsBeforeUsageReleaseAndRestoresInterrupt() {
        val process=child("ignore");var closed=false
        try {
            Thread.currentThread().interrupt()
            AutoCloseable {assertFalse(process.isAlive);closed=true}.use {WarpChildProcess.stop(process,graceMillis=10)}
            assertTrue(closed);assertTrue(Thread.currentThread().isInterrupted)
        } finally {Thread.interrupted();if(process.isAlive){process.destroyForcibly();process.waitFor()}}
    }
}
