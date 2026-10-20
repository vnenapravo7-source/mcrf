package org.mcrf.client
import org.junit.Assert.*
import org.junit.Test
class ClientPrimaryIdentityProofTest {
    @Test fun selfBirthUsesStartTimeAfterFullCommandName() {
        val fields=MutableList(30){"0"}.apply {this[0]="S";this[19]="123456"}
        assertEquals("123456",ClientPrimaryIdentityProof.parseBirth("42 (worker name (with) parentheses) ${fields.joinToString(" ")}"))
    }
    @Test fun malformedOrMissingProofNeverAuthenticates() {
        for(value in listOf(null,"","0","-1"," 123","123\n","1x","1".repeat(25)))assertFalse(ClientPrimaryIdentityProof.validSelfProof("private-binder-self-v1",value))
        assertFalse(ClientPrimaryIdentityProof.validSelfProof(null,"123"))
        for(value in listOf("", "123", "42 (worker) S 0"))assertThrows(IllegalArgumentException::class.java){ClientPrimaryIdentityProof.parseBirth(value)}
    }
}
