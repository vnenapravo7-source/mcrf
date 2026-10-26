package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SteerHelperPreparationTest {
    @Test fun noChildBeforePhysicalPlatformInitialization(){
        val calls=mutableListOf<Long>();val pending=SteerHelperPreparation {handle->calls.add(handle);"private-ingress"}
        assertTrue(calls.isEmpty())
        assertThrows(IllegalArgumentException::class.java){pending.materialize(null)}
        assertThrows(IllegalArgumentException::class.java){pending.materialize(0)}
        assertTrue(calls.isEmpty())
        assertEquals("private-ingress",pending.materialize(437197393933L))
        assertEquals(listOf(437197393933L),calls)
    }
    @Test fun preparedIngressCannotBeReusedAfterOwnerChanges(){
        var calls=0;val pending=SteerHelperPreparation {handle->calls++;handle}
        assertEquals(101L,pending.materialize(101L))
        assertThrows(IllegalStateException::class.java){pending.materialize(102L)}
        assertEquals(1,calls)
    }
    @Test fun failedOpeningDoesNotRetryTheSamePrivateOwner(){
        var calls=0;val pending=SteerHelperPreparation<String> {calls++;throw IllegalStateException("owned opening failure")}
        assertThrows(IllegalStateException::class.java){pending.materialize(101L)}
        assertThrows(IllegalStateException::class.java){pending.materialize(101L)}
        assertEquals(1,calls)
    }
}
