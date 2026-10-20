package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ServerClientSearchTest {
    private val names=listOf("Дом", "Рабочий телефон", "Рабочий телефон")
    private val ids=listOf("home-1","phone-2","phone-3")
    @Test fun blankRestoresOriginalOrderAndNoMatchIsEmpty(){
        assertEquals(listOf(0,1,2),ServerClientSearch.indices(names,ids," \n "))
        assertTrue(ServerClientSearch.indices(names,ids,"несуществующий").isEmpty())
    }
    @Test fun filteredRowsRetainExactIdentityWithDuplicateNames(){
        val indices=ServerClientSearch.indices(names,ids,"РАБОЧИЙ phone-3")
        assertEquals(listOf(2),indices)
        assertEquals("phone-3",ids[indices.single()])
        assertEquals(listOf(1,2),ServerClientSearch.indices(names,ids,"телефон"))
    }
    @Test fun searchIsLiteralAndRequiresAlignedRoster(){
        assertTrue(ServerClientSearch.indices(names,ids,".*").isEmpty())
        assertThrows(IllegalArgumentException::class.java){ServerClientSearch.indices(names,emptyList(),"")}
    }
}
