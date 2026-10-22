package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test
class DirectAccessBudgetTest {
    @Test fun parallelDeadlinePreservesOriginalPerRequestTimeouts(){
        assertEquals(2500,DirectAccessCheck.CONNECT_TIMEOUT_MS)
        assertEquals(2500,DirectAccessCheck.READ_TIMEOUT_MS)
        assertEquals((DirectAccessCheck.CONNECT_TIMEOUT_MS+DirectAccessCheck.READ_TIMEOUT_MS).toLong(),DirectAccessCheck.BUDGET_MS)
    }
}
