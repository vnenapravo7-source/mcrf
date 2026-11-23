package org.mcrf.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Queued for main's source gate. No assets, storage, clocks, network or device admission. */
class RoutingValidationMemoTest {
    private fun input(value: String) = JSONObject().put("value", value).toString()

    @Test fun exactCapturedContentHitsButEveryChangedFieldAndFormattingMisses() {
        var calls = 0
        val memo = RoutingValidationMemo({ calls++; require(it.getBoolean("valid")) })
        memo.validate("{\"valid\":true,\"extra\":1}")
        memo.validate("{\"valid\":true,\"extra\":1}")
        assertEquals(1, calls)
        memo.validate("{\"valid\":true,\"extra\":2}")
        memo.validate("{ \"valid\":true,\"extra\":2 }")
        assertEquals(3, calls)
    }

    @Test fun stringHashCollisionDoesNotTurnAnotherInputIntoSuccess() {
        val good = input("Aa"); val bad = input("BB")
        assertEquals(good.hashCode(), bad.hashCode())
        var calls = 0
        val memo = RoutingValidationMemo({ calls++; require(it.getString("value") == "Aa") })
        memo.validate(good)
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { memo.validate(bad) } }
        assertEquals(3, calls)
        assertEquals(1, memo.stats().entries)
    }

    @Test fun privateInputAndImmutableKeyCannotBePoisonedByCallerOrRetainedJson() {
        val source = JSONObject().put("valid", true).put("nested", JSONArray().put(JSONObject().put("text", "original")))
        val captured = JSONObject(source.toString())
        var received: JSONObject? = null
        var calls = 0
        val memo = RoutingValidationMemo({ copy ->
            calls++
            assertNotSame(source, copy)
            assertNotSame(source.getJSONArray("nested"), copy.getJSONArray("nested"))
            assertNotSame(source.getJSONArray("nested").getJSONObject(0), copy.getJSONArray("nested").getJSONObject(0))
            source.put("valid", false).getJSONArray("nested").getJSONObject(0).put("text", "changed")
            require(copy.getBoolean("valid") && copy.getJSONArray("nested").getJSONObject(0).getString("text") == "original")
            received = copy
        })
        memo.validate(source)
        received!!.put("valid", false).getJSONArray("nested").getJSONObject(0).put("text", "poisoned")
        memo.validate(captured)
        assertEquals(1, calls)
        assertThrows(IllegalArgumentException::class.java) { memo.validate(source) }
        assertEquals(2, calls)
    }

    @Test fun failuresPublishNothingAndLeaveNoPendingEntryBlockingAnotherAttempt() {
        var calls = 0
        val memo = RoutingValidationMemo({ calls++; if (calls <= 2) error("fixture failure") })
        repeat(2) {
            assertThrows(IllegalStateException::class.java) { memo.validate(JSONObject(input("same"))) }
            assertEquals(RoutingValidationMemo.Stats(0, 0), memo.stats())
        }
        memo.validate(JSONObject(input("same"))); memo.validate(JSONObject(input("same")))
        assertEquals(3, calls)
        assertEquals(1, memo.stats().entries)
    }

    @Test fun recursiveChildSuccessCannotPublishFailedParentAndDoesNotDeadlock() {
        var outer = 0; var inner = 0
        lateinit var memo: RoutingValidationMemo
        memo = RoutingValidationMemo({ copy ->
            if (copy.getString("value") == "outer") {
                outer++; memo.validate(JSONObject(input("inner"))); error("parent invalid after child validation")
            } else inner++
        })
        repeat(2) { assertThrows(IllegalStateException::class.java) { memo.validate(JSONObject(input("outer"))) } }
        assertEquals(2, outer); assertEquals(1, inner)
        assertEquals(1, memo.stats().entries)
    }

    @Test fun simultaneousValidationRunsOutsideCacheLockAndDuplicateSuccessPublishesOnce() {
        val workers = Executors.newFixedThreadPool(4)
        val entered = CountDownLatch(4); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val memo = RoutingValidationMemo({
            calls.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val tasks = (1..4).map { workers.submit<Unit> { memo.validate(JSONObject(input("same"))) } }
        try {
            // All callbacks must enter before ANY finishes; serializing validation under the cache lock fails this.
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(4, calls.get())
            assertEquals(1, memo.stats().entries)
            memo.validate(JSONObject(input("same"))); assertEquals(4, calls.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun concurrentDifferentInputsRespectCombinedEntryAndByteBudgets() {
        val workers = Executors.newFixedThreadPool(4)
        val memo = RoutingValidationMemo({}, budgetBytes = 1700)
        try {
            val tasks = (1..64).map { value -> workers.submit<Unit> { memo.validate(JSONObject(input(value.toString()))) } }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(memo.stats().entries <= RoutingValidationMemo.MAX_ENTRIES)
            assertTrue(memo.stats().retainedBytes <= 1700)
        } finally { workers.shutdownNow() }
        val entries = RoutingValidationMemo({})
        repeat(64) { entries.validate(JSONObject(input(it.toString()))) }
        assertEquals(16, entries.stats().entries)
        assertTrue(entries.stats().retainedBytes <= 8 * 1024 * 1024)
    }

    @Test fun oversizeAndDisabledCacheStillValidateEveryTimeAndLimitsCannotBeRaised() {
        var calls = 0
        val oversize = RoutingValidationMemo({ calls++ }, budgetBytes = 512)
        repeat(2) { oversize.validate(JSONObject(input("valid but not retained"))) }
        assertEquals(2, calls); assertEquals(RoutingValidationMemo.Stats(0, 0), oversize.stats())
        val disabled = RoutingValidationMemo({ calls++ }, maxEntries = 0)
        repeat(2) { disabled.validate(JSONObject(input("same"))) }
        assertEquals(4, calls); assertEquals(0, disabled.stats().entries)
        assertThrows(IllegalArgumentException::class.java) { RoutingValidationMemo({}, budgetBytes = 8 * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { RoutingValidationMemo({}, maxEntries = 17) }
    }

    @Test fun leastRecentlyUsedSuccessIsEvicted() {
        var calls = 0
        val memo = RoutingValidationMemo({ calls++ }, maxEntries = 2)
        for (value in listOf("a", "b", "a", "c", "b")) memo.validate(input(value))
        assertEquals(4, calls)
    }

    @Test fun exactNumericTokensAreNotCanonicalizedByMemoIntoTheSameTypedProof() {
        var calls = 0
        val memo = RoutingValidationMemo({ calls++; require(it.get("number") is Int) })
        memo.validate("{\"number\":1}")
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { memo.validate("{\"number\":1.0}") } }
        assertEquals(3, calls)
        assertEquals(1, memo.stats().entries)
    }

    @Test fun schemaAndLimitsRemainFailClosedAfterFullInputWasCached() {
        val valid = RouteRules.defaults()
        RoutingStore.validate(valid); RoutingStore.validate(JSONObject(valid.toString()))
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(JSONObject(valid.toString()).put("version", 2)) }
        val rules = JSONObject(valid.toString()).put("rules", JSONArray(List(1025) { JSONObject() }))
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(rules) }
        val apps = JSONObject(valid.toString()).put("excludedApps", JSONArray(List(257) { "org.fixture.app$it" }))
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(apps) }
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(JSONObject(valid.toString()).put("defaultTarget", "invalid")) }
    }

    @Test fun outerExperienceTemporaryAndInactiveGroupChecksCannotBeBypassedByFullMemo() {
        val source = RouteRules.defaults().apply { RoutingGroups.ensure(this); ExperienceRouting.ensure(this) }
        RoutingStore.validate(source)
        val brokenBank = JSONObject(source.toString()).apply { getJSONObject(ExperienceRouting.KEY).put("version", 2) }
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(brokenBank) }
        val uncheckedBankField = JSONObject(source.toString()).apply {
            getJSONObject(ExperienceRouting.KEY).getJSONObject("simple").put("unexpected", true)
        }
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(uncheckedBankField) }
        val brokenTemporary = JSONObject(source.toString()).put(TemporaryRules.KEY, JSONObject().put("version", 2).put("sessions", JSONArray()))
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(brokenTemporary) }
        val temporaryField = JSONObject(source.toString()).put(TemporaryRules.KEY,
            JSONObject().put("version", 1).put("sessions", JSONArray()).put("unexpected", true))
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(temporaryField) }
        val inactiveGroup = JSONObject(source.toString()).apply {
            getJSONObject(RoutingGroups.KEY).getJSONArray("groups").getJSONObject(1)
                .getJSONObject("data").put("defaultTarget", "invalid")
        }
        assertThrows(IllegalArgumentException::class.java) { RoutingStore.validate(inactiveGroup) }
        // Existing baseline clones the core before its strict primitive checks; preserve that behavior.
        val integer = RouteRules.defaults().put("version", 1)
        val decimal = RouteRules.defaults().put("version", 1.0)
        assertEquals(integer.toString(), decimal.toString())
        RoutingStore.validate(integer); RoutingStore.validate(decimal)
    }

    @Test fun typedSnapshotDistinguishesDoubleFromIntBeforeStrictOuterValidation() {
        val integer = JSONObject().put("outer", JSONObject().put("number", 1))
        val decimal = JSONObject().put("outer", JSONObject().put("number", 1.0))
        assertEquals(integer.toString(), decimal.toString())
        var calls = 0
        val memo = RoutingValidationMemo({ calls++; require(it.getJSONObject("outer").get("number") is Int) })
        memo.validate(integer); memo.validate(integer)
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { memo.validate(decimal) } }
        assertEquals(3, calls)
        assertEquals(1, memo.stats().entries)
        // Reverse order also cannot poison the later integer proof.
        val reverse = RoutingValidationMemo({ require(it.getJSONObject("outer").get("number") is Int) })
        assertThrows(IllegalArgumentException::class.java) { reverse.validate(decimal) }
        reverse.validate(integer)
        assertEquals(1, reverse.stats().entries)
    }

    @Test fun allSupportedNumberClassesAndFloatingBitsSurviveDetachedCopy() {
        val numbers: List<Number> = listOf(1.toByte(), 1.toShort(), 1, 1L, 1f, 1.0,
            BigInteger.ONE, BigDecimal("1.0"), BigDecimal("1.00"), 0.0, -0.0, 0f, -0f)
        var calls = 0
        var expected: Number = 1
        val memo = RoutingValidationMemo({ copy ->
            calls++
            val actual = copy.getJSONArray("values").get(0)
            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected, actual)
        })
        for (number in numbers) {
            expected = number
            val source = JSONObject().put("values", JSONArray().put(number))
            memo.validate(source); memo.validate(source)
        }
        assertEquals(numbers.size, calls)
        assertEquals(numbers.size, memo.stats().entries)
    }

    @Test fun typedEncodingCannotConfuseKeysStringsNullsOrContainerBoundaries() {
        val sources = listOf(
            JSONObject().put("a", "b").put("c", "d"), JSONObject().put("a", "bs1:ck1:ds0:"),
            JSONObject().put("a", JSONArray().put(JSONObject.NULL)), JSONObject().put("a", "n;"),
            JSONObject().put("a", JSONObject()), JSONObject().put("a", JSONArray()),
            JSONObject().put("a", true), JSONObject().put("a", "T;"),
            JSONObject().put("ab", "c"), JSONObject().put("a", "bc"), JSONObject())
        var calls = 0
        val memo = RoutingValidationMemo({ calls++ })
        sources.forEach { memo.validate(it); memo.validate(it) }
        assertEquals(sources.size, calls)
    }

    @Test fun unknownMutableNumberAndCustomContainersAlwaysUseUncachedOriginalValidation() {
        val number = AtomicInteger(1)
        val source = JSONObject().put("number", number)
        var calls = 0
        val memo = RoutingValidationMemo({ original -> calls++; assertSame(source, original); require(number.get() == 1) })
        memo.validate(source); memo.validate(source)
        number.set(2)
        assertThrows(IllegalArgumentException::class.java) { memo.validate(source) }
        assertEquals(3, calls); assertEquals(RoutingValidationMemo.Stats(0, 0), memo.stats())
        val custom = object : JSONObject() {}
        var customCalls = 0
        val customMemo = RoutingValidationMemo({ customCalls++; assertSame(custom, it) })
        repeat(2) { customMemo.validate(custom) }
        assertEquals(2, customCalls); assertEquals(0, customMemo.stats().entries)
    }

    @Test fun captureDepthNodeAndByteBoundsFallBackWithoutPublishingOrSkippingValidation() {
        val deep = JSONObject()
        var cursor = deep
        repeat(70) { val child = JSONObject(); cursor.put("child", child); cursor = child }
        val wide = JSONObject().put("values", JSONArray(List(100_001) { true }))
        val large = JSONObject().put("value", "x".repeat(5000))
        for (source in listOf(deep, wide, large)) {
            var calls = 0
            val memo = RoutingValidationMemo({ calls++; assertSame(source, it) },
                budgetBytes = if (source === large) 4096 else RoutingValidationMemo.MAX_BYTES)
            repeat(2) { memo.validate(source) }
            assertEquals(2, calls); assertEquals(RoutingValidationMemo.Stats(0, 0), memo.stats())
        }
        val cycle = JSONObject().also { it.put("self", it) }
        val cyclic = RoutingValidationMemo({ assertSame(cycle, it) })
        cyclic.validate(cycle)
        assertEquals(0, cyclic.stats().entries)
    }
}
