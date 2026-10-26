package org.mcrf.client

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PrimaryFallbackFailureGuardTest {
    @Test fun backupCannotBypassATrustRejectionOrCancellation(){
        for(error in listOf(SecurityException("trust rejected"),InterruptedException("cancelled"),CancellationException("cancelled"),
            IOException("wrapper",CompactProtocolPolicy.UnsafeProfile("host key required"))))assertFalse(PrimaryFallbackPolicy.canRetryFailure(error))
    }
    @Test fun ordinaryStartupFailureRemainsEligibleButCircularCauseDoesNot(){
        assertTrue(PrimaryFallbackPolicy.canRetryFailure(IOException("ordinary connection failure")))
        val first=IOException("first");val second=IOException("second",first);first.initCause(second)
        assertFalse(PrimaryFallbackPolicy.canRetryFailure(first))
    }
}
