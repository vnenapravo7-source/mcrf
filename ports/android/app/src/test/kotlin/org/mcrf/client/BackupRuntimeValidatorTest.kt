package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class BackupRuntimeValidatorTest {
    @Test fun missingInstalledCoreRequiresInstallAndNeverCommits() {
        var committed = false
        try {
            AndroidBackup.runtimeValidatedCommit("fixture-config", null) { committed = true }
            fail("Missing primary core accepted")
        } catch(error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("установите")) }
        assertFalse(committed)
    }

    @Test fun deniedRuntimeConfigurationKeepsOriginalGeneration() {
        var generation = "original"
        try {
            AndroidBackup.runtimeValidatedCommit("unsupported-fixture", { throw IllegalArgumentException("unsupported primary capability") }) { generation = "replacement" }
            fail("Denied config committed")
        } catch(error: IllegalArgumentException) { assertEquals("unsupported primary capability", error.message) }
        assertEquals("original", generation)
    }

    @Test fun validatorExceptionPropagatesWithoutPartialCommit() {
        val failure = java.io.IOException("fixture validator unavailable")
        var commits = 0
        try {
            AndroidBackup.runtimeValidatedCommit("fixture", { throw failure }) { commits++ }
            fail("Unavailable validator committed")
        } catch(error: java.io.IOException) { assertSame(failure,error) }
        assertEquals(0,commits)
    }

    @Test fun validationReceivesExactConfigurationBeforeSingleCommit() {
        val events = mutableListOf<String>()
        AndroidBackup.runtimeValidatedCommit("exact-fixture", { events.add(it); assertEquals(listOf("exact-fixture"),events) }) { events.add("commit") }
        assertEquals(listOf("exact-fixture","commit"),events)
    }

    @Test fun oldBackupWithoutProfileNeedsNoCoreButStillCommitsOnce() {
        var commits = 0
        AndroidBackup.runtimeValidatedCommit(null,null) { commits++ }
        assertEquals(1,commits)
    }
}
