package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ProbeObserverReviewTest {
    private fun publishStateChange() {
        ProbeCoordinator::class.java.getDeclaredMethod("changed").apply {isAccessible=true}.invoke(ProbeCoordinator)
    }
    @Test fun failedPresentationObserverDoesNotAbortStatePublication() {
        var delivered=0
        val failing:()->Unit={throw IllegalStateException("detached fixture view")}
        val healthy:()->Unit={delivered++}
        ProbeCoordinator.observe(failing);ProbeCoordinator.observe(healthy)
        try {publishStateChange();assertEquals(1,delivered)}
        finally {ProbeCoordinator.unobserve(failing);ProbeCoordinator.unobserve(healthy)}
    }
    @Test fun observerCanUnsubscribeDuringPublicationWithoutBreakingOthers() {
        var firstCount=0;var secondCount=0
        lateinit var first:()->Unit
        first={firstCount++;ProbeCoordinator.unobserve(first)}
        val second:()->Unit={secondCount++}
        ProbeCoordinator.observe(first);ProbeCoordinator.observe(second)
        try {publishStateChange();publishStateChange();assertEquals(1,firstCount);assertEquals(2,secondCount)}
        finally {ProbeCoordinator.unobserve(first);ProbeCoordinator.unobserve(second)}
    }
}
