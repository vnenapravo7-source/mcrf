package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SubscriptionPartialImportTest {
    private val good="vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls&type=tcp#Working"
    @Test fun subscriptionKeepsValidEntriesAroundBadOnes(){
        val parsed=ServerImport.readSubscription("$good\nunsupported://fixture\nvless://broken\n$good")
        assertEquals(1,parsed.servers.size)
        assertEquals(2,parsed.skipped)
    }
    @Test fun manualImportRemainsStrict(){
        try {ServerImport.read("$good\nunsupported://fixture");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun nonUuidVlessEntryDoesNotAbortSubscription(){
        val invalid="vless://not-a-valid-userid@example.com:443?security=tls&type=ws"
        val parsed=ServerImport.readSubscription("# provider metadata\n$good\n$invalid")
        assertEquals(1,parsed.servers.size)
        assertEquals(1,parsed.skipped)
    }
    @Test fun noUsableEntriesIsStillFailure(){
        try {ServerImport.readSubscription("unsupported://fixture\nvless://broken");fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun documentSizeLimitCannotBeSkipped(){
        try {ServerImport.readSubscription("$good\n"+"x".repeat(ServerImport.MAX_BYTES));fail()}catch(_:IllegalArgumentException){}
    }
}
