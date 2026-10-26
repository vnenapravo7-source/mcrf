package org.mcrf.client

import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.*
import org.junit.Test

class FallbackTrustReviewTest {
    @Test fun certificateValidationDoesNotBecomeRetryableWhenWrapped() {
        val handshake=SSLHandshakeException("fixture").apply {initCause(CertificateException("untrusted fixture"))}
        assertFalse(PrimaryFallbackPolicy.canRetryFailure(IOException("outer",handshake)))
    }
    @Test fun peerIdentityAndCertificatePathRejectionsRemainTerminal() {
        for(error in listOf(SSLPeerUnverifiedException("fixture"),CertPathValidatorException("fixture")))
            assertFalse(PrimaryFallbackPolicy.canRetryFailure(IOException("outer",error)))
    }
    @Test fun finiteWorkerIdentityFailuresNeverStartAReplacement() {
        for(code in listOf("PRIMARY_WORKER_PID_IDENTITY","PRIMARY_WORKER_HANDSHAKE","PRIMARY_WORKER_MANIFEST_PROCESS",
            "PRIMARY_CORE_APK_HASH","PRIMARY_CORE_NATIVE_CLASSLOADER","PRIMARY_WORKER_UID","PRIMARY_WORKER_OWNER"))
            assertFalse(PrimaryFallbackPolicy.canRetryFailure(IOException("PRIMARY_WORKER_COMMAND_FAILED",IllegalStateException(code))))
    }
    @Test fun pendingOwnershipAndWireInterruptionDoNotRetry() {
        for(code in listOf("PRIMARY_WORKER_CLEANUP_PENDING","PRIMARY_WORKER_DEATH_TIMEOUT_LEASE_RETAINED",
            "PRIMARY_WORKER_COMPONENT_BUSY","PRIMARY_CORE_UPDATE_BUSY","PRIMARY_WORKER_STOPPING","PRIMARY_WORKER_INTERRUPTED"))
            assertFalse(PrimaryFallbackPolicy.canRetryFailure(IllegalStateException(code)))
    }
    @Test fun ordinaryConnectivityAndBindingFailuresRemainEligibleForOptedInFallback() {
        for(error in listOf(IOException("connection reset"),java.net.SocketTimeoutException("fixture"),IllegalStateException("PRIMARY_WORKER_BIND_TIMEOUT")))
            assertTrue(PrimaryFallbackPolicy.canRetryFailure(error))
    }
    @Test fun arbitraryMessageIsNotTreatedAsATrustDecision() {
        assertTrue(PrimaryFallbackPolicy.canRetryFailure(IOException("metadata mentions certificate or PRIMARY_CORE_APK_HASH as text")))
    }
    @Test fun workerWirePreservesExactIdentityCodeThroughGenericWrapper() {
        val code=ClientPrimaryPreparationFailure.wireCode(IOException("PRIMARY_WORKER_COMMAND_FAILED",IllegalStateException("PRIMARY_CORE_APK_HASH")),"PRIMARY_CORE_LOAD_FAILED")
        assertEquals("PRIMARY_CORE_APK_HASH",code);assertFalse(PrimaryFallbackPolicy.canRetryFailure(IllegalStateException(code)))
    }
    @Test fun workerWireKeepsTerminalTypesWithoutPrivateExceptionMessages() {
        for((error,expected) in listOf(SecurityException("fixture-secret") to "PRIMARY_WORKER_SECURITY_DENIED",
            CertificateException("fixture-secret") to "PRIMARY_WORKER_TRUST_REJECTED",InterruptedException("fixture-secret") to "PRIMARY_WORKER_INTERRUPTED")) {
            val code=ClientPrimaryPreparationFailure.wireCode(IOException("outer",error),"PRIMARY_CORE_RUN_FAILED")
            assertEquals(expected,code);assertFalse(PrimaryFallbackPolicy.canRetryFailure(IllegalStateException(code)))
            assertFalse(ClientPrimaryPreparationFailure.message(code).contains("fixture-secret"))
        }
    }
    @Test fun workerWireUnknownFailureUsesOnlyTheControlledFallback() {
        assertEquals("PRIMARY_CORE_RUN_FAILED",ClientPrimaryPreparationFailure.wireCode(IOException("fixture-secret"),"PRIMARY_CORE_RUN_FAILED"))
    }
    @Test fun cyclicAndOverlongWorkerCausesFailClosed() {
        val first=IOException("one");val second=IOException("two",first);first.initCause(second)
        var deep:Throwable=IOException("end");repeat(17){deep=IOException("wrapper",deep)}
        for(error in listOf(first,deep)) {
            val code=ClientPrimaryPreparationFailure.wireCode(error,"PRIMARY_CORE_RUN_FAILED")
            assertEquals("PRIMARY_WORKER_FAILURE_CHAIN_INVALID",code);assertFalse(PrimaryFallbackPolicy.canRetryFailure(IllegalStateException(code)))
        }
    }
    @Test fun ordinaryWorkerFailureRemainsRetryableAfterTheWire() {
        val code=ClientPrimaryPreparationFailure.wireCode(java.net.SocketTimeoutException("fixture"),"PRIMARY_CORE_RUN_FAILED")
        assertTrue(PrimaryFallbackPolicy.canRetryFailure(IllegalStateException(code)))
    }
}
