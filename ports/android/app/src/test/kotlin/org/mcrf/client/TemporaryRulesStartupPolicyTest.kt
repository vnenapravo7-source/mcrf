package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Pure branch decisions only; no claim to exercise Android stores, receiver delivery or a VPN. */
class TemporaryRulesStartupPolicyTest {
    @Test fun onlyBootAndOwnPackageReplacementCanUseIdleMetadataPath() {
        for (action in listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")) {
            val startup = TemporaryRulesStartupPolicy.isStartupAction(action)
            assertTrue(startup)
            assertTrue(TemporaryRulesStartupPolicy.metadataOnly(startup, hasActiveSession = false))
            assertFalse(TemporaryRulesStartupPolicy.metadataOnly(startup, hasActiveSession = true))
        }
    }

    @Test fun expireTimeChangedUnknownAndMissingActionsCannotUseMetadataPath() {
        for (action in listOf(null, "", "org.mcrf.client.TEMPORARY_RULES_EXPIRE", "android.intent.action.TIME_SET",
                "android.intent.action.PACKAGE_REPLACED", "android.intent.action.LOCKED_BOOT_COMPLETED", "internal-reconcile")) {
            val startup = TemporaryRulesStartupPolicy.isStartupAction(action)
            assertFalse(startup)
            for (active in listOf(false, true)) assertFalse(TemporaryRulesStartupPolicy.metadataOnly(startup, active))
        }
    }

    @Test fun defaultInternalReconcileAlwaysKeepsFullValidation() {
        assertFalse(TemporaryRulesStartupPolicy.metadataOnly(hasActiveSession = false))
        assertFalse(TemporaryRulesStartupPolicy.metadataOnly(hasActiveSession = true))
    }

    @Test fun ownedActiveSessionAlwaysKeepsFullValidationRegardlessOfAction() {
        for (startup in listOf(false, true)) assertFalse(TemporaryRulesStartupPolicy.metadataOnly(startup, true))
    }
}
