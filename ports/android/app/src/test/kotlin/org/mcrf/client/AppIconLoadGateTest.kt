package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AppIconLoadGateTest {
    @Test fun repeatedBindingsShareOnePendingLoad() {
        val gate=AppIconLoadGate()
        assertTrue(gate.reserve("app"))
        repeat(1000){assertFalse(gate.reserve("app"))}
        gate.release("app")
        assertTrue(gate.reserve("app"))
    }
    @Test fun scrollingCannotCreateUnboundedQueuedWork() {
        val gate=AppIconLoadGate(2)
        assertTrue(gate.reserve("a"));assertTrue(gate.reserve("b"))
        repeat(1000){assertFalse(gate.reserve("other-$it"))}
        gate.release("a");assertTrue(gate.reserve("c"));assertFalse(gate.reserve("d"))
    }
    @Test fun cleanupAndFailuresFreeCapacityWithoutAffectingOtherKeys() {
        val gate=AppIconLoadGate(2)
        gate.reserve("a");gate.reserve("b");gate.release("absent")
        assertFalse(gate.reserve("c"))
        gate.release("a");assertTrue(gate.reserve("c"))
        gate.clear();assertTrue(gate.reserve("a"));assertTrue(gate.reserve("b"))
        assertThrows(IllegalArgumentException::class.java){AppIconLoadGate(0)}
    }
}
