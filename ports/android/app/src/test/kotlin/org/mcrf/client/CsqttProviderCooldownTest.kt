package org.mcrf.client

import org.junit.Assert.assertEquals
import org.junit.Test

class CsqttProviderCooldownTest {
    @Test fun cohortUsesSharedTypedProviderDuration() {
        for(kind in VkCredentialLimit.Kind.values())for(seconds in listOf(600,900,1200))
            assertEquals(1000L+seconds*1000L,CsqttProviderCooldown.deadline(1000,VkCredentialLimit(kind,seconds)))
    }
    @Test fun noProviderFeedbackAddsNoCohortPause() {
        assertEquals(0L,CsqttProviderCooldown.deadline(1000,null))
    }
}
