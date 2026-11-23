package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring/privacy only; does not measure Android clocks or startup time. */
class RoutingValidationTraceSourceContractTest {
    private fun source()=listOf(
        File("src/main/kotlin/org/mcrf/client/RoutingStore.kt"),
        File("app/src/main/kotlin/org/mcrf/client/RoutingStore.kt")
    ).first {it.isFile}.readText().replace("\r\n","\n")
    private fun validator()=source().substringAfter("private fun validateUncached(source: JSONObject) {")
        .substringBefore("private fun validateCore(")

    @Test fun everyFullValidationPhaseHasFixedLabelAndOriginalOrder() {
        val body=validator()
        val labels=Regex("validationStage\\(traceId,\"([a-z_]+)\"\\)").findAll(body).map {it.groupValues[1]}.toList()
        assertEquals(listOf("temp","experience","geo_editor","clone","geo_compile","core"),labels)
        val operations=listOf("TemporaryRules.validate(source)","ExperienceRouting.validate(source)",
            "RouteEditorDraft.validateGeo(TextGeoRules.editableText(rules.getJSONObject(i)),source)",
            "JSONObject(source.toString()).apply {remove(ExperienceRouting.KEY)}",
            "TextGeoRules.compile(settings)","validateCore(settings)")
        val positions=operations.map {body.indexOf(it)}
        assertTrue(positions.all {it>=0})
        assertTrue(positions.zipWithNext().all {(before,after)->before<after})
        for(operation in operations)assertEquals(1,body.split(operation).size-1)
        assertFalse(body.contains("runCatching"));assertFalse(body.contains("return"))
    }

    @Test fun releaseAllocatesNoTraceCounterAndDoesNotReadClockOrLog() {
        val all=source()
        assertTrue(all.contains("private val validationTraceCount = if (BuildConfig.DEBUG) java.util.concurrent.atomic.AtomicInteger() else null"))
        assertTrue(validator().contains("val traceId = if (BuildConfig.DEBUG) validationTraceCount!!.incrementAndGet().let { if (it in 1..8) it else 0 } else 0"))
        val helper=all.substringAfter("private inline fun <T> validationStage(").substringBefore("/** Release transitive path:")
        val exit=helper.indexOf("if (!BuildConfig.DEBUG || callId == 0) return action()")
        assertTrue(exit>=0)
        assertTrue(helper.indexOf("System.nanoTime()")>exit)
        assertTrue(helper.indexOf("android.util.Log.d(")>exit)
        assertTrue(helper.contains("try { return action() } finally {"))
        assertTrue(helper.contains("runCatching {"))
    }

    @Test fun timingPayloadContainsOnlyInvocationNumberFixedStageAndElapsedTime() {
        val all=source()
        val helper=all.substringAfter("private inline fun <T> validationStage(").substringBefore("/** Release transitive path:")
        assertTrue(helper.contains("android.util.Log.d(\"MCRF_STARTUP\", \"routing_validate.call=\$callId stage=\$stage elapsed_ms=\$elapsed\")"))
        for(sensitive in listOf("JSONObject","JSONArray","source","toString","getString","optString","ruleId","exception","message","stackTrace"))
            assertFalse(sensitive,helper.contains(sensitive))
        assertEquals(2,helper.split("System.nanoTime()").size-1)
        assertFalse(helper.contains("android.os.SystemClock"))
        assertFalse(helper.contains("currentTimeMillis"))
        assertFalse(helper.contains("Thread.currentThread"))
    }

    @Test fun validatorTimingNeedsNoAndroidClockAndNeverCatchesValidationFailure() {
        val helper=source().substringAfter("private inline fun <T> validationStage(")
            .substringBefore("/** Release transitive path:")
        assertTrue(helper.contains("val started = System.nanoTime()"))
        assertFalse(helper.contains("SystemClock"))
        val action=helper.indexOf("try { return action() } finally {")
        val logging=helper.indexOf("runCatching {")
        assertTrue(action>=0&&logging>action)
        assertEquals(1,helper.split("runCatching {").size-1)
        assertFalse(helper.contains("catch ("))
    }

    @Test fun fullValidationMemoAndRecursiveCoreAdmissionRemainUnchanged() {
        val all=source()
        assertTrue(all.contains("private val structuralValidation = RoutingValidationMemo(::validateUncached)"))
        assertTrue(all.contains("@JvmStatic fun validate(source: JSONObject) {\n            structuralValidation.validate(source)"))
        val core=all.substringAfter("private fun validateCore(settings: JSONObject) {")
        assertTrue(core.contains("RoutingGroups.validate(settings)"))
        assertTrue(core.contains("RoutingConnectionBinding.validate(settings)"))
        assertTrue(core.contains("require(rules.length() <= 1024)"))
        assertTrue(core.contains("GeoDataConfig::validate"))
    }
}
