package org.mcrf.client

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ImportFailureReviewTest {
    @Test fun possiblyCommittedWriteNeverClaimsThatConnectionsWereNotAdded() {
        val detail=ImportFailure.describe(IOException("revision failed after write"),"accepting")
        assertTrue(detail.startsWith("IMPORT_WRITE_UNCONFIRMED:"))
        assertFalse(detail.contains("не добавлены"));assertTrue(detail.contains("проверьте текущие данные"))
    }
    @Test fun commitExceptionMessageDoesNotLeakProfilesOrSecrets() {
        val detail=ImportFailure.describe(IllegalArgumentException("fixture-secret https://private.invalid/token"),"accepting")
        assertFalse(detail.contains("fixture-secret"));assertFalse(detail.contains("private.invalid"))
    }
    @Test fun confirmedCommitWithFailedPresentationRetainsItsOwnMessage() {
        val detail=ImportFailure.describe(IOException("private path"),"refreshing")
        assertTrue(detail.startsWith("Подключения сохранены"));assertFalse(detail.contains("private path"))
    }
}
