package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class RemoteServerOperationGateTest {
    @Test fun otherOperationCannotStartOrReleaseTheCurrentOwner(){
        val owner=java.util.UUID.randomUUID().toString()
        try{
            assertTrue(RemoteServerOperationGate.acquire(owner))
            assertFalse(RemoteServerOperationGate.acquire("other"))
            RemoteServerOperationGate.release("other")
            assertTrue(RemoteServerOperationGate.busy())
        }finally{RemoteServerOperationGate.release(owner)}
        assertFalse(RemoteServerOperationGate.busy())
    }
}
