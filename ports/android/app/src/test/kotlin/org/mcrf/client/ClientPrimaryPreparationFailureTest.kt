package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ClientPrimaryPreparationFailureTest {
    @Test fun genericCommandWrapperDoesNotHideIdentityOrRetirementFailure() {
        for(code in listOf("PRIMARY_WORKER_PID_IDENTITY","PRIMARY_WORKER_CLEANUP_PENDING","PRIMARY_CORE_LOAD_FAILED")) {
            assertEquals(code,ClientPrimaryPreparationFailure.code(IllegalStateException("PRIMARY_WORKER_COMMAND_FAILED",IllegalStateException(code))))
        }
        assertEquals("PRIMARY_WORKER_COMMAND_FAILED",ClientPrimaryPreparationFailure.code(IllegalStateException("PRIMARY_WORKER_COMMAND_FAILED",IllegalStateException("password=private"))))
        var deep:Throwable=IllegalStateException("PRIMARY_WORKER_PID_IDENTITY")
        repeat(8){deep=IllegalStateException("private",deep)}
        assertNull(ClientPrimaryPreparationFailure.code(deep))
    }
    @Test fun workerLoadAndRunFailuresRemainSpecificAfterWrapping() {
        for(code in listOf("PRIMARY_CORE_LOAD_FAILED","PRIMARY_CORE_RUN_FAILED","PRIMARY_CORE_EXITED_BEFORE_RUNNING","PRIMARY_WORKER_COMMAND_FAILED","PRIMARY_WORKER_DISCONNECTED")) {
            val error=IllegalStateException("PRIMARY_WORKER_FAILED",IllegalStateException(code))
            assertEquals(code,ClientPrimaryPreparationFailure.code(error))
            assertTrue(ClientPrimaryPreparationFailure.message(code).contains("Код: $code"))
        }
        assertTrue(ClientPrimaryPreparationFailure.message("PRIMARY_WORKER_OWNER").contains("Проверка подлинности"))
        assertNull(ClientPrimaryPreparationFailure.code(IllegalStateException("PRIMARY_CORE_RUN_FAILED: password=private")))
    }
    @Test fun knownStartupAndIdentityCodesExplainWithoutTreatingThemAsServerPasswordFailure() {
        for(code in listOf("PRIMARY_WORKER_BIND_TIMEOUT","PRIMARY_WORKER_PID_IDENTITY","PRIMARY_WORKER_FAILED")){
            assertEquals(code,ClientPrimaryPreparationFailure.code(IllegalStateException(code)))
            assertTrue(ClientPrimaryPreparationFailure.message(code).contains("Код: $code"))
        }
        assertTrue(ClientPrimaryPreparationFailure.message("PRIMARY_WORKER_CLEANUP_PENDING").contains("не будет запущен параллельно"))
    }
    @Test fun untrustedMessagesNeverBecomeUiOrLogCodesAndCauseTraversalIsBounded() {
        val private="wdtt://connect?password=private"
        assertNull(ClientPrimaryPreparationFailure.code(IllegalStateException(private)))
        assertNull(ClientPrimaryPreparationFailure.code(IllegalStateException("PRIMARY_PASSWORD_PRIVATE")))
        assertThrows(IllegalArgumentException::class.java){ClientPrimaryPreparationFailure.message(private)}
        val wrapped=IllegalStateException("PRIMARY_WORKER_FAILED",IllegalStateException("PRIMARY_WORKER_PID_IDENTITY"))
        assertEquals("PRIMARY_WORKER_PID_IDENTITY",ClientPrimaryPreparationFailure.code(wrapped))
        val cycle=IllegalStateException(private);cycle.initCause(IllegalStateException(private,cycle))
        assertNull(ClientPrimaryPreparationFailure.code(cycle))
    }
}
