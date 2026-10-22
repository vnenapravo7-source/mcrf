package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

/** Parser/identity proof only, NOT Android process-group death acceptance. */
class CarrierProcessGroupProofTest {
    private val nonce="a".repeat(32)
    private fun kernelRoot(pid:Int=321,parent:Int=123,group:Int=pid,birth:String="456"):CarrierProcessGroupProof.Root {
        val fields=MutableList(25){"0"};fields[0]="S";fields[1]=parent.toString();fields[2]=group.toString();fields[19]=birth
        return CarrierProcessGroupProof.stat(pid,"$pid (owned (native) command) ${fields.joinToString(" ")}")
    }
    @Test fun exactNoncePidAndBirthAreRequiredWithoutEchoingPrivateInput() {
        assertEquals(321 to "456",CarrierProcessGroupProof.record("MCRF_PROCESS_GROUP_V1|$nonce|321|456",nonce))
        for(value in listOf("MCRF_PROCESS_GROUP_V1|${"b".repeat(32)}|321|456","MCRF_PROCESS_GROUP_V1|$nonce|0321|456",
            "MCRF_PROCESS_GROUP_V1|$nonce|1|456","MCRF_PROCESS_GROUP_V1|$nonce|321|0","MCRF_PROCESS_GROUP_V1|$nonce|321|456|extra")) {
            val error=assertThrows(IllegalArgumentException::class.java){CarrierProcessGroupProof.record(value,nonce)}
            assertEquals("CARRIER_GROUP_RECORD",error.message)
        }
    }
    @Test fun nestedCommandParenthesesDoNotShiftKernelBirthOrGroup() {
        val actual=kernelRoot();assertEquals(321,actual.pid);assertEquals(123,actual.parent)
        assertEquals(321,actual.group);assertEquals("456",actual.birth)
    }
    private fun owned(root:CarrierProcessGroupProof.Root=kernelRoot(),birth:String="456",uid:Int=10001,exe:String="/installed/native",cwd:String="/private/run")=
        CarrierProcessGroupProof.owned(root,birth,123,uid,10001,exe,"/installed/native",cwd,"/private/run")
    @Test fun parentUidBirthExecutableDirectoryAndDedicatedGroupAreAllNecessary() {
        assertTrue(owned())
        assertFalse(owned(kernelRoot(parent=124)));assertFalse(owned(kernelRoot(group=123)))
        assertFalse(owned(birth="457"));assertFalse(owned(uid=10002))
        assertFalse(owned(exe="/foreign/native"));assertFalse(owned(cwd="/private/previous-run"))
        assertFalse(owned(kernelRoot(pid=123,parent=123)))
    }
    @Test fun reusedPidWithDifferentBirthNeverMatchesOriginalOwner() {
        assertFalse(owned(kernelRoot(birth="999")))
        assertThrows(IllegalArgumentException::class.java){CarrierProcessGroupProof.stat(321,"322 (wrong) S 123")}
        assertThrows(IllegalArgumentException::class.java){CarrierProcessGroupProof.stat(321,"321 (short) S 123")}
    }
}
