package org.mcrf.client

import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test

class ManagedAppFreezeAdbTest {
    @Test
    fun attachRequiresExplicitUnexpiredOneTimeActivationFromShellOrRoot() {
        val gate = FreezeAdbActivation()
        assertFalse(gate.consume(2000, null, 0))
        val nonce = gate.issue(1)
        assertFalse(gate.consume(10042, nonce, 2))
        assertFalse(gate.consume(102000, nonce, 2))
        assertFalse(gate.consume(2000, "wrong", 2))
        assertTrue(gate.consume(2000, nonce, 2))
        assertFalse(gate.consume(0, nonce, 3))

        val replaced = gate.issue(4)
        val current = gate.issue(5)
        assertFalse(gate.consume(0, replaced, 6))
        assertFalse(gate.consume(0, current, 300_005))
        val canceled = gate.issue(7)
        gate.cancel()
        assertFalse(gate.consume(2000, canceled, 8))
    }

    @Test
    fun localActivationRequiresShellAndExpiresAfterFifteenSeconds() {
        val gate = FreezeAdbActivation()
        val nonce = gate.issue(1, local = true)
        assertFalse(gate.consume(0, nonce, 2))
        assertFalse(gate.consume(10042, nonce, 2))
        assertTrue(gate.consume(2000, nonce, 15_000))
        assertFalse(gate.consume(2000, nonce, 15_000))

        val expired = gate.issue(20_000, local = true)
        assertFalse(gate.consume(2000, expired, 35_000))
    }

    @Test
    fun cancelingAnOldLocalAttemptDoesNotInvalidateTheCurrentAttempt() {
        val gate = FreezeAdbActivation()
        val previous = gate.issue(1, local = true)
        val current = gate.issue(2, local = true)
        gate.cancel(previous)
        assertTrue(gate.consume(2000, current, 3))

        val canceled = gate.issue(4, local = true)
        gate.cancel(canceled)
        assertFalse(gate.consume(2000, canceled, 5))
    }

    @Test
    fun localHelperAcceptsOnlyACanonicalActivationNonce() {
        val nonce = "93b4ff9c-ab4c-4dd5-a770-466780b7bfb1"
        val ownerPackage = requireNotNull(ManagedAppFreezeAdb.ownerPackageForBuild())
        val command = ManagedAppFreezeAdb.LocalActivation(nonce, ownerPackage).shellCommand
        assertTrue(command.contains("pm path --user 0 $ownerPackage |"))
        assertTrue(command.contains("/system/bin/setsid app_process /system/bin org.mcrf.client.ManagedAppFreezeAdbDaemon $nonce"))
        assertTrue(command.endsWith("</dev/null >/dev/null 2>&1 &"))
        assertThrows(IllegalStateException::class.java) {
            ManagedAppFreezeAdb.LocalActivation("$nonce; id", ownerPackage).shellCommand
        }
    }

    @Test
    fun onlyTheExactDebugCloneCanUseItsOwnAuthority() {
        val canonical = ManagedAppFreezeAdb.PACKAGE
        val clone = ManagedAppFreezeAdb.UI_CLONE_PACKAGE
        assertEquals(canonical, ManagedAppFreezeAdb.ownerPackageForBuild(canonical, false))
        assertEquals(canonical, ManagedAppFreezeAdb.ownerPackageForBuild(canonical, true))
        assertEquals(clone, ManagedAppFreezeAdb.ownerPackageForBuild(clone, true))
        assertNull(ManagedAppFreezeAdb.ownerPackageForBuild(clone, false))
        assertNull(ManagedAppFreezeAdb.ownerPackageForBuild("$clone.other", true))
        assertNull(ManagedAppFreezeAdb.ownerPackageForBuild("$canonical.anubispairingfixture", true))
        assertEquals("$clone.freeze-adb", ManagedAppFreezeAdb.authority(clone))
        assertThrows(IllegalArgumentException::class.java) { ManagedAppFreezeAdb.validateTarget(canonical, clone) }
        assertThrows(IllegalArgumentException::class.java) { ManagedAppFreezeAdb.validateTarget(clone, clone) }
        ManagedAppFreezeAdb.validateTarget("org.example.app", clone)
    }

    @Test
    fun onlyTheExactOwnerAppUidCanUseTheBinder() {
        assertTrue(FreezeAdbActivation.canUse(10042, 10042))
        assertFalse(FreezeAdbActivation.canUse(10042, 10043))
        assertFalse(FreezeAdbActivation.canUse(10042, 2000))
        assertFalse(FreezeAdbActivation.canUse(110042, 110042))
    }

    @Test
    fun onlyKnownPackageStateChangesAndSafeTargetsAreAccepted() {
        assertEquals("disable-user", ManagedAppFreezeCommand.actionForState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertEquals("enable", ManagedAppFreezeCommand.actionForState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        assertEquals("default-state", ManagedAppFreezeCommand.actionForState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertEquals("disable-until-used", ManagedAppFreezeCommand.actionForState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED))
        assertThrows(IllegalStateException::class.java) {
            ManagedAppFreezeCommand.actionForState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }
        val owner = "org.mcrf.client"
        listOf(owner, "moe.shizuku.privileged.api", "org.example;id", "--user", "org.example/Viewer").forEach {
            assertThrows(IllegalArgumentException::class.java) { ManagedAppFreezeCommand.validateTarget(it, owner) }
        }
        ManagedAppFreezeCommand.validateTarget("org.example.app", owner)
    }
}
