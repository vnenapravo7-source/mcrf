package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Callback-order models, not real WebView/renderer or Activity teardown proof. */
class OpenFluxWebViewRetirementReviewTest {
    private fun run(failing: Set<Int>): List<Int> {
        val calls = mutableListOf<Int>()
        fun step(index: Int): () -> Unit = { calls.add(index); if (index in failing) throw IllegalStateException("synthetic") }
        OpenFluxWebViewRetirement.retire(step(0), step(1), step(2), step(3), step(4))
        return calls
    }
    @Test fun removalAlwaysPrecedesStopAndDestroyIsLast() { assertEquals((0..4).toList(), run(emptySet())) }
    @Test fun failedRemovalDoesNotSkipRemainingRetirement() { assertEquals((0..4).toList(), run(setOf(0))) }
    @Test fun failedStopDoesNotSkipSetterCleanupOrDestroy() { assertEquals((0..4).toList(), run(setOf(1))) }
    @Test fun failedSettersDoNotSkipDestroy() { assertEquals((0..4).toList(), run(setOf(2, 3))) }
    @Test fun allThrowingStepsStillAttemptEveryStepOnceWithoutExceptionEcho() { assertEquals((0..4).toList(), run((0..4).toSet())) }
}
