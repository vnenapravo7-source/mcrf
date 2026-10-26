package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class CompactProtocolLogPrivacyTest {
    @Test fun protocolLinksNeverExposeCredentialsInLogs(){
        for(scheme in listOf("tuic","anytls","naive","ssh","socks","socks5")){
            val link="$scheme://private-user:private-password@example.test:443?private-token#private-room"
            val result=ClientEvents.redact("error: $link")
            assertTrue(result.startsWith("error:"));assertFalse(result.contains(link))
            for(secret in listOf("private-user","private-password","private-token","private-room"))assertFalse(result.contains(secret))
        }
    }
}
