package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientWarpSelectionJournalTest {
    private val old = "1".repeat(64)
    private val next = "2".repeat(64)
    private val operation = "00000000-0000-0000-0000-000000000001"
    private fun journal(selected: String = old, previous: String? = old) = ClientWarpSelectionJournal.create(selected, previous, next, "files", operation)
    private fun proof() = JSONObject().put("previousId", old).put("newId", next).put("sequence", 1)

    @Test fun durableMatchingOperationRecoversSelection() {
        assertEquals(next, ClientWarpSelectionJournal.recover(journal(), old, "files", setOf(next)) { id -> assertEquals(operation, id); proof() })
    }
    @Test fun existingProfileAloneDoesNotProveCommit() {
        assertNull(ClientWarpSelectionJournal.recover(journal(), old, "files", setOf(old, next)) { null })
    }
    @Test fun laterSelectionOrBackupGenerationDoesNotGetOverwritten() {
        assertNull(ClientWarpSelectionJournal.recover(journal(), next, "files", setOf(next)) { proof() })
        assertNull(ClientWarpSelectionJournal.recover(journal(), old, "mcrf-store-other", setOf(next)) { proof() })
        assertNull(ClientWarpSelectionJournal.recover(journal(), old, "files", emptySet()) { proof() })
    }
    @Test fun mismatchedReceiptNeverAdvancesSelection() {
        assertNull(ClientWarpSelectionJournal.recover(journal(), old, "files", setOf(next)) { proof().put("newId", old) })
        assertNull(ClientWarpSelectionJournal.recover(journal(), old, "files", setOf(next)) { proof().put("previousId", next) })
        assertNull(ClientWarpSelectionJournal.recover(journal(), old, "files", setOf(next)) { proof().apply { remove("previousId") } })
    }
    @Test fun emptySelectionIsDifferentFromAnExistingReplacementId() {
        assertEquals(next, ClientWarpSelectionJournal.recover(journal("", old), "", "files", setOf(next)) { proof() })
        assertEquals(next, ClientWarpSelectionJournal.recover(journal("", null), "", "files", setOf(next)) { JSONObject().put("newId", next) })
        assertNull(ClientWarpSelectionJournal.recover(journal("", null), "", "files", setOf(next)) { proof() })
    }
    @Test fun malformedAndOldExistenceOnlyJournalsFailClosed() {
        assertNull(ClientWarpSelectionJournal.recover("{", old, "files", setOf(next)) { proof() })
        assertNull(ClientWarpSelectionJournal.recover(JSONObject().put("old", old).put("next", next).put("generation", "files").toString(), old, "files", setOf(next)) { proof() })
    }
}
