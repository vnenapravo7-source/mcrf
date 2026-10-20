package org.mcrf.client

import org.json.JSONObject
import java.util.UUID

/** Selection preferences advance only after the same operation's encrypted catalogue commit. */
object ClientWarpSelectionJournal {
    private val profileId = Regex("[0-9a-f]{64}")

    fun create(oldSelection: String, previousId: String?, nextId: String, generation: String, operationId: String): String {
        require(oldSelection.isEmpty() || oldSelection.matches(profileId))
        require(previousId == null || previousId.matches(profileId))
        require(nextId.matches(profileId) && generation.length in 1..256)
        require(UUID.fromString(operationId).toString() == operationId)
        return JSONObject().put("old", oldSelection).put("next", nextId).put("generation", generation)
            .put("operationId", operationId).apply { if (previousId != null) put("previousId", previousId) }.toString()
    }

    fun recover(raw: String, selected: String, generation: String, existingIds: Set<String>, receipt: (String) -> JSONObject?): String? {
        if (raw.length > 4096) return null
        return try {
            val journal = JSONObject(raw)
            val operation = journal.getString("operationId")
            require(UUID.fromString(operation).toString() == operation)
            val old = journal.getString("old")
            val next = journal.getString("next")
            val previous = if (journal.has("previousId")) journal.getString("previousId") else null
            require(old.isEmpty() || old.matches(profileId))
            require(previous == null || previous.matches(profileId))
            require(next.matches(profileId) && next in existingIds && selected == old)
            require(journal.getString("generation") == generation)
            val proof = receipt(operation) ?: return null
            require(proof.getString("newId") == next)
            require(if (previous == null) !proof.has("previousId") else proof.optString("previousId") == previous)
            next
        } catch (_: Exception) { null }
    }
}
