package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionBudgetReviewTest {
    @Test fun independentResponsesShareOneByteBudget() {
        val budget=SubscriptionPayloadBudget(10)
        budget.reserve(sequenceOf("12345"));budget.reserve(sequenceOf("67890"))
        try {budget.reserve(sequenceOf("x"));fail("Must reject aggregate overflow")}
        catch(_:IllegalArgumentException) { }
    }
    @Test fun unicodeIsCountedAsBytesNotCharacters() {
        val budget=SubscriptionPayloadBudget(4)
        budget.reserve(sequenceOf("яя"))
        try {budget.reserve(sequenceOf("a"));fail("Must count UTF-8 bytes")}
        catch(_:IllegalArgumentException) { }
    }
    @Test fun rejectedResponseDoesNotPartiallyConsumeBudget() {
        val budget=SubscriptionPayloadBudget(5)
        try {budget.reserve(sequenceOf("123","456"));fail("Must reject")}
        catch(_:IllegalArgumentException) { }
        budget.reserve(sequenceOf("12345"))
    }
    @Test fun productionPathsCountManualProfilesAndSubscriptionMetadata() {
        val nodes=ServerImport.read("socks://127.0.0.1:1080#fixture")
        val metadata=JSONObject().put("title","fixture")
        val url="https://fixture.invalid/sub"
        val expected=nodes.single().original.toByteArray().size*2+url.toByteArray().size+metadata.toString().toByteArray().size
        val budget=SubscriptionPayloadBudget(expected.toLong())
        budget.acceptManual(nodes);budget.accept(url,SubscriptionClient.Downloaded(nodes,metadata))
        try {budget.reserve(sequenceOf("x"));fail("All retained content must count")}
        catch(_:IllegalArgumentException) { }
    }
    @Test fun budgetFailureHasControlledPublicDiagnostic() {
        val error=IllegalArgumentException(SubscriptionPayloadBudget.MESSAGE)
        assertEquals("SUBSCRIPTION_REJECTED: "+SubscriptionPayloadBudget.MESSAGE,SubscriptionFailure.describe(error))
    }
}
