package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompatibilityImportValidationTest {
    private val link = "vless://11111111-1111-4111-8111-111111111111@example.invalid:443?security=tls#Fixture"
    private val legacy = CompatibilityImportValidation.Validator { fail("Unexpected fallback") }
    private fun fixture(): ConnectionCatalogue = ConnectionCatalogue("").apply {
        replaceSubscription("https://example.invalid/sub", ServerImport.read(link), JSONObject().put("title", "Fixture").put("deviceLimit", 2))
        val entry=entries().single(); edit(entry.id, ConnectionEdits.snapshot(entry).put("name", "Saved"))
    }
    @Test fun legacyPathStillValidatesBeforeCommit() {
        val gate=CompatibilityImportValidation(); val order=mutableListOf<String>()
        gate.validateAndCommit(ServerImport.read(link).single(), CompatibilityImportValidation.Validator { order.add("check") }) { order.add("commit") }
        assertEquals(listOf("check", "commit"),order)
    }
    @Test fun absentFailureAndCancellationPreserveOverlaysAndMetadata() {
        val data=fixture(); val before=data.serialize(); val gate=CompatibilityImportValidation()
        val candidate=data.entries().single()
        for (validator in listOf(null, CompatibilityImportValidation.Validator { throw IllegalArgumentException("Unsupported") }, CompatibilityImportValidation.Validator { throw java.util.concurrent.CancellationException() })) {
            gate.useInstalledPrimary(validator)
            try { gate.validateAndCommit(candidate,legacy) { data.edit(candidate.id,ConnectionEdits.snapshot(candidate).put("name","Changed")) }; fail() }
            catch (_: RuntimeException) { }
            assertEquals(before,data.serialize())
        }
    }
    @Test fun validatorMutationDoesNotChangeSavedProfileOrShare() {
        val data=fixture(); val before=data.serialize(); val candidate=data.entries().single(); val share=ConnectionEdits.share(candidate,true)
        val gate=CompatibilityImportValidation(); gate.useInstalledPrimary { it.outbound!!.put("server","mutated.invalid") }
        var committed=false; gate.validateAndCommit(candidate,legacy) { committed=true }
        assertTrue(committed); assertEquals(before,data.serialize()); assertEquals(share,ConnectionEdits.share(candidate,true))
    }
    @Test fun changedRegistrationRejectsStaleValidation() {
        val gate=CompatibilityImportValidation(); var committed=false
        gate.useInstalledPrimary { gate.useInstalledPrimary(null) }
        try { gate.validateAndCommit(ServerImport.read(link).single(),legacy) { committed=true }; fail() }
        catch (_: IllegalStateException) { }
        assertFalse(committed)
    }
}
