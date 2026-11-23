package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsDegradationTest {
    @Test fun connectionEvidenceNeedsIndependentMajorityAndIgnoresDuplicateOrigin() {
        fun row(id:String,state:DiagnosticState=DiagnosticState.AVAILABLE,ms:Long?=100)=DiagnosticResult(id,
            DiagnosticPath.CURRENT_MCRF,DiagnosticFamily.AUTOMATIC,state,
            if(state==DiagnosticState.ERROR)DiagnosticIssue.TIMEOUT else DiagnosticIssue.NONE,ms)
        val one=row("google-site")
        assertEquals(DiagnosticState.UNAVAILABLE,ConnectionDegradationEvidence.aggregate(listOf(one,one),one.path).state)
        val mixed=listOf(one,row("cloudflare-site",ms=120),row("microsoft-store-site",DiagnosticState.ERROR,null))
        val evidence=ConnectionDegradationEvidence.aggregate(mixed,one.path)
        assertEquals("connection",evidence.targetId);assertEquals(DiagnosticState.AVAILABLE,evidence.state)
        assertEquals(100L,evidence.elapsedMs)
        assertEquals(DiagnosticState.ERROR,ConnectionDegradationEvidence.aggregate(listOf(one,
            row("cloudflare-site",DiagnosticState.ERROR,null),row("microsoft-store-site",DiagnosticState.ERROR,null)),one.path).state)
        assertEquals(DiagnosticState.UNAVAILABLE,ConnectionDegradationEvidence.aggregate(mixed,DiagnosticPath.DIRECT).state)
    }
    private val settings = DegradationSettings(enabled = true, targetIds = setOf("github-site"), minimumBaseline = 3)
    private fun sample(ms: Long = 100, state: DiagnosticState = DiagnosticState.AVAILABLE, issue: DiagnosticIssue = DiagnosticIssue.NONE) =
        DiagnosticResult("github-site", DiagnosticPath.CURRENT_MCRF, DiagnosticFamily.AUTOMATIC, state, issue, ms)
    private fun trained(policy: DiagnosticsDegradationPolicy) { for (at in 1L..3L) assertNull(policy.sample(sample(), null, at, false)) }
    @Test fun requiresBaselineRepeatedDeviationAndCooldownWithoutLearningFault() {
        val policy = DiagnosticsDegradationPolicy(settings); trained(policy)
        assertNull(policy.sample(sample(500), null, 4, false)); assertNull(policy.sample(sample(500), null, 5, false))
        val recommendation = policy.sample(sample(500), null, 6, false)!!
        assertEquals(100L, recommendation.baselineMs); assertEquals(3, recommendation.failures); assertFalse(recommendation.fallbackConfigured)
        assertEquals(3, policy.baselineSamples()); assertNull(policy.sample(sample(500), null, 7, false))
        assertNotNull(policy.sample(sample(500), null, 900_006, true))
    }
    @Test fun unavailableAndHttpPolicyBreakConsecutiveFailures() {
        val policy = DiagnosticsDegradationPolicy(settings); trained(policy)
        val failure = sample(state = DiagnosticState.ERROR, issue = DiagnosticIssue.TIMEOUT)
        assertNull(policy.sample(failure, null, 4, false)); assertNull(policy.sample(failure, null, 5, false))
        assertNull(policy.sample(sample(state = DiagnosticState.UNAVAILABLE), null, 6, false))
        assertNull(policy.sample(failure, null, 7, false)); assertNull(policy.sample(failure, null, 8, false))
        assertNull(policy.sample(sample(state = DiagnosticState.ERROR, issue = DiagnosticIssue.HTTP_REJECTED), null, 9, false))
        assertNull(policy.sample(failure, null, 10, false))
    }
    @Test fun resetPreventsCrossNetworkBaselineAndDuplicateTimesAreIgnored() {
        val policy = DiagnosticsDegradationPolicy(settings); trained(policy)
        policy.sample(sample(), null, 3, false); assertEquals(3, policy.baselineSamples())
        policy.reset(); assertEquals(0, policy.baselineSamples())
        repeat(3) { assertNull(policy.sample(sample(600), null, 10L + it, false)) }
    }
    @Test fun optOutAndOtherFamiliesCannotGenerateRecommendation() {
        val policy = DiagnosticsDegradationPolicy(settings.copy(enabled = false))
        for (at in 1L..20L) assertNull(policy.sample(sample(1000), null, at, true))
        assertEquals(0, policy.baselineSamples())
        val enabled = DiagnosticsDegradationPolicy(settings)
        enabled.sample(sample().copy(family = DiagnosticFamily.IPV6), null, 1, false)
        assertEquals(0, enabled.baselineSamples())
    }
}
