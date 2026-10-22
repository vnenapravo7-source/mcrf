package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class QuickTileCommandTest {
    @Test fun separateTicketRecordsKeepRapidActionsIndependentAndRejectMalformedAge() {
        val first="00000000-0000-0000-0000-000000000001"
        val second="00000000-0000-0000-0000-000000000002"
        val record=org.json.JSONObject().put("command","rules").put("at",1000).toString()
        assertEquals("rules",QuickTileCommand.decode(first,record,2000))
        assertEquals("rules",QuickTileCommand.decode(second,record,2000))
        assertNull(QuickTileCommand.decode(first,null,2000))
        assertNull(QuickTileCommand.decode("forged",record,2000))
        assertNull(QuickTileCommand.decode(first,record,61_001))
        assertNull(QuickTileCommand.decode(first,"{\"command\":\"rules\"}",0))
        assertNull(QuickTileCommand.decode(first,"{\"command\":\"rules\",\"at\":1.5}",2000))
    }
    @Test fun commandNeedsAnExactFreshPrivateTicketAndAllowlistedAction() {
        for(command in listOf("vpn","bs","rules","auto-bs")) {
            assertTrue(QuickTileCommand.valid("owned-ticket","owned-ticket",command,0))
            assertTrue(QuickTileCommand.valid("owned-ticket","owned-ticket",command,60_000))
            assertFalse(QuickTileCommand.valid("forged-ticket","owned-ticket",command,0))
            assertFalse(QuickTileCommand.valid(null,"owned-ticket",command,0))
            assertFalse(QuickTileCommand.valid("owned-ticket","owned-ticket",command,-1))
            assertFalse(QuickTileCommand.valid("owned-ticket","owned-ticket",command,60_001))
        }
        assertFalse(QuickTileCommand.valid("owned-ticket","owned-ticket","install",0))
        assertFalse(QuickTileCommand.valid("owned-ticket","owned-ticket",null,0))
        assertFalse(QuickTileCommand.valid("","","vpn",0))
    }
}
