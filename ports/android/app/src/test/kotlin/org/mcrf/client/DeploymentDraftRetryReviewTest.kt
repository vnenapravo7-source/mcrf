package org.mcrf.client

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DeploymentDraftRetryReviewTest {
    @Test fun repeatedSubmissionUpdatesTheSameAcknowledgedDraft() {
        val draft=DeploymentDraftRetry();var creates=0;val updated=mutableListOf<String>()
        repeat(3){assertEquals("fixture-id",draft.save({creates++;"fixture-id"},{updated.add(it)}))}
        assertEquals(1,creates);assertEquals(listOf("fixture-id","fixture-id"),updated)
    }
    @Test fun latestEditedFieldsReachTheUpdateInsteadOfCreatingAnotherDraft() {
        val draft=DeploymentDraftRetry();var title="first";var saved=""
        draft.save({saved=title;"fixture-id"},{saved=title})
        title="edited";draft.save({fail("Must update");"unreachable"},{saved=title})
        assertEquals("edited",saved)
    }
    @Test fun failedUpdateDoesNotDiscardTheAcknowledgedId() {
        val draft=DeploymentDraftRetry();draft.save({"fixture-id"},{fail("Unexpected update")})
        try {draft.save({fail("Must not create");"unreachable"},{throw IOException()});fail("Must fail")}
        catch(_:IOException) { }
        assertEquals("fixture-id",draft.save({fail("Must not create");"unreachable"},{assertEquals("fixture-id",it)}))
    }
    @Test fun failedCreateDoesNotInventAnAcknowledgedId() {
        val draft=DeploymentDraftRetry()
        try {draft.save({throw IOException()},{fail("No ID exists")});fail("Must fail")}
        catch(_:IOException) { }
        assertEquals("retry-id",draft.save({"retry-id"},{fail("No ID was acknowledged")}))
    }
}
