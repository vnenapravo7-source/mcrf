package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ClientP1OwnerScopePinTest {
    @Test fun pinBindsBothOrderedArtifactHashesWithSeparateContractDomain(){
        val helper="a".repeat(64);val peer="b".repeat(64)
        val value=ClientP1OwnerFixtureScope.pin(helper,peer)
        assertTrue(value.matches(Regex("[0-9a-f]{64}")));assertNotEquals(helper,value);assertNotEquals(peer,value)
        assertNotEquals(value,ClientP1OwnerFixtureScope.pin(peer,helper))
        assertNotEquals(value,ClientP1OwnerFixtureScope.pin(helper,"c".repeat(64)))
    }
    @Test fun partialUppercaseOrNonHashInputCannotBecomeAnOwnerScope(){
        for(value in listOf("","a".repeat(63),"A".repeat(64),"g".repeat(64)))
            assertThrows(IllegalArgumentException::class.java){ClientP1OwnerFixtureScope.pin("a".repeat(64),value)}
    }
}
