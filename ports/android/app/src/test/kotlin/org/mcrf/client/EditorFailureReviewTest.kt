package org.mcrf.client

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EditorFailureReviewTest {
    @Test fun nativeRejectionKeepsOnlyItsFiniteTechnicalCode(){
        val result=EditorSaveFailure.describe(IllegalArgumentException("UNKNOWN_FIELD:profile password=fixture-secret https://private.invalid"),EditorSaveFailure.Phase.VALIDATE)
        assertTrue(result.startsWith("UNKNOWN_FIELD:"));assertFalse(result.contains("fixture-secret"));assertFalse(result.contains("private.invalid"))
    }
    @Test fun untrustedUppercaseTextDoesNotBecomeAPublicCode(){
        val result=EditorSaveFailure.describe(IllegalArgumentException("PRIVATE_FIXTURE_SECRET PASSWORD_TOKEN"),EditorSaveFailure.Phase.VALIDATE)
        assertTrue(result.startsWith("EDITOR_VALIDATION_FAILED:"));assertFalse(result.contains("PRIVATE_FIXTURE_SECRET"))
    }
    @Test fun writeFailureAndValidationFailureAreDifferentEvenForSameIOException(){
        val error=IOException("private file path and secret")
        assertTrue(EditorSaveFailure.describe(error,EditorSaveFailure.Phase.SAVE).startsWith("EDITOR_WRITE_FAILED:"))
        assertTrue(EditorSaveFailure.describe(error,EditorSaveFailure.Phase.VALIDATE).startsWith("EDITOR_VALIDATION_FAILED:"))
    }
    @Test fun wrappedSecurityAndCancellationRemainTerminalAndRecognizable(){
        for((error,code) in listOf(IOException("outer",SecurityException("private")) to "EDITOR_SECURITY_DENIED",
            CancellationException("private") to "EDITOR_CANCELLED",InterruptedException("private") to "EDITOR_CANCELLED"))
            assertTrue(EditorSaveFailure.describe(error,EditorSaveFailure.Phase.SAVE).startsWith(code))
    }
    @Test fun changedPrimaryHasItsOwnActionableReason(){
        val result=EditorSaveFailure.describe(IllegalStateException("Основное ядро изменилось во время проверки. Повтори сохранение."),EditorSaveFailure.Phase.VALIDATE)
        assertTrue(result.startsWith("EDITOR_ENGINE_CHANGED:"));assertTrue(result.contains("Повторите сохранение"))
    }
    @Test fun failedValidationNeverReachesDurableCommitAndItsCodeSurvives(){
        val gate=CompatibilityImportValidation();var committed=false
        gate.useInstalledPrimary {throw IllegalArgumentException("PRIMARY_CORE_CONFIG_REJECTED")}
        val entry=ServerImport.read("socks://127.0.0.1:1080#owned-review").single()
        try{gate.validateAndCommit(entry,CompatibilityImportValidation.Validator {error("Legacy path must not run")}){committed=true};fail("Must reject")}
        catch(error:IllegalArgumentException){assertTrue(EditorSaveFailure.describe(error,EditorSaveFailure.Phase.VALIDATE).startsWith("PRIMARY_CORE_CONFIG_REJECTED:"))}
        assertFalse(committed)
    }
    @Test fun exceptionCyclesAreFiniteAndMessagesNeverLeak(){
        val first=IOException("fixture-secret");val second=IOException("private",first);first.initCause(second)
        assertTrue(EditorSaveFailure.describe(first,EditorSaveFailure.Phase.SAVE).startsWith("EDITOR_WRITE_FAILED:"))
    }
    @Test fun uncertainWriteNeverClaimsThatTheOldVersionIsStillOnDisk(){
        val detail=EditorSaveFailure.describe(IOException("Could have committed before metadata reload"),EditorSaveFailure.Phase.SAVE)
        assertFalse(detail.contains("Последний корректный вариант сохранён"));assertTrue(detail.contains("проверки текущих данных"))
    }
}
