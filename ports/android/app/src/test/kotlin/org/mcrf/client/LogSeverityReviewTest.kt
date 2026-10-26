package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class LogSeverityReviewTest {
    private fun entry(message: String) = LogEntries.parse("2026-10-25 01:00:00 [Ядро] $message")

    @Test fun certificateValidationFailuresAreVisibleInProblems() {
        for (message in listOf("x509: certificate signed by unknown authority", "x509: certificate has expired", "x509: certificate is valid for example.invalid, not requested.invalid")) {
            val row = entry(message)
            assertEquals(LogEntries.Level.ERROR, row.level)
            assertEquals(listOf(row), LogEntries.filter(listOf(row), LogEntries.Group.PROBLEMS))
        }
    }
    @Test fun javaTrustFailuresAreErrorsWithoutExtraFailureWords() {
        for (name in listOf("SSLHandshakeException", "SSLPeerUnverifiedException", "CertPathValidatorException"))
            assertEquals(LogEntries.Level.ERROR, entry(name).level)
    }
    @Test fun tlsHandshakeRejectionIsAnError() {
        assertEquals(LogEntries.Level.ERROR, entry("TLS handshake error: peer rejected handshake").level)
    }
    @Test fun interruptedConnectionsAndDeadlinesStayInProblems() {
        for (message in listOf("context deadline exceeded", "connection reset by peer", "broken pipe")) {
            val row = entry(message)
            assertEquals(LogEntries.Level.WARNING, row.level)
            assertEquals(1, LogEntries.filter(listOf(row), LogEntries.Group.PROBLEMS).size)
        }
    }
    @Test fun SuccessfulTlsDoesNotBecomeAnErrorJustBecauseItMentionsCertificates() {
        for (message in listOf("TLS handshake completed", "certificate loaded", "x509 certificate configured"))
            assertEquals(LogEntries.Level.INFO, entry(message).level)
    }
    @Test fun filteredProblemsKeepRedactedTechnicalDetails() {
        val row = entry("SSLHandshakeException token=fixture-secret")
        assertEquals(LogEntries.Level.ERROR, row.level)
        assertFalse(row.details.contains("fixture-secret"))
        assertTrue(row.details.contains("SSLHandshakeException"))
    }
}
