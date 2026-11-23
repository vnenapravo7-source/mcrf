package org.mcrf.client

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeCarrierRuntimeCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun removesShortSlipstreamRuntimeWithoutRemovingNeighbors() {
        val cache = temporary.newFolder("cache")
        val runtime = cache.resolve("mcrf-s-0123456789abcdef").apply { mkdir() }
        runtime.resolve("config.json").writeText("{}")
        val neighbor = cache.resolve("keep").apply { writeText("keep") }

        NativeCarrier.deleteRuntimeDirectory(cache, runtime)

        assertFalse(runtime.exists())
        assertTrue(neighbor.isFile)
    }

    @Test fun rejectsWrongParentAndMalformedName() {
        val cache = temporary.newFolder("cache")
        val elsewhere = temporary.newFolder("elsewhere")
        val runtime = elsewhere.resolve("mcrf-s-0123456789abcdef").apply { mkdir() }
        val config = runtime.resolve("config.json").apply { writeText("{}") }
        assertThrows(IOException::class.java) { NativeCarrier.deleteRuntimeDirectory(cache, runtime) }
        val malformed = cache.resolve("mcrf-s-0123456789abcde").apply { mkdir() }
        assertThrows(IOException::class.java) { NativeCarrier.deleteRuntimeDirectory(cache, malformed) }
        assertTrue(malformed.isDirectory)
        assertTrue(config.isFile)
    }

    @Test fun rejectsSymbolicLink() {
        val cache = temporary.newFolder("cache")
        val runtime = cache.resolve("mcrf-s-0123456789abcdef").apply { mkdir() }
        val config = runtime.resolve("config.json").apply { writeText("{}") }
        val link = cache.resolve("mcrf-s-fedcba9876543210")
        try {
            Files.createSymbolicLink(link.toPath(), runtime.toPath())
        } catch (error: IOException) {
            org.junit.Assume.assumeNoException(error)
        }
        assertThrows(IOException::class.java) { NativeCarrier.deleteRuntimeDirectory(cache, link) }
        assertTrue(config.isFile)
    }
}
