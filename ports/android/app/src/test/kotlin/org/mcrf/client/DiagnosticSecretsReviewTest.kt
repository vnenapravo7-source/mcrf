package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DiagnosticSecretsReviewTest {
    @Test fun quotedPasswordWithSpacesAndEscapedQuoteIsEntirelyHidden() {
        val output=ClientEvents.redact("""TLS failed {"password":"fixture alpha\" tail", "code":503}""")
        assertFalse(output.contains("fixture"));assertFalse(output.contains("tail"));assertTrue(output.contains("503"))
    }
    @Test fun multilineHashArrayHidesAllEntriesAndQuotedBrackets() {
        val output=DiagnosticSecrets.redact("\"hashes\": [\"fixture]one\",\n\"fixture-two\"], code=503")
        assertFalse(output.contains("fixture"));assertTrue(output.contains("503"))
    }
    @Test fun bareHashListDoesNotExposeEntriesAfterFirstComma() {
        assertFalse(DiagnosticSecrets.redact("hashes=fixture-a,fixture-b,fixture-c; code=503").contains("fixture"))
    }
    @Test fun vkCallPathWithoutQueryIsHidden() {
        listOf("vk.com","vk.ru","m.vk.com","m.vk.ru:443").forEach {host->
            val output=ClientEvents.redact("Connect https://$host/call/join/fixture-call-token HTTP 503")
            assertFalse(output.contains("fixture-call-token"));assertTrue(output.contains("503"))
        }
    }
    @Test fun truncatedNestedSecretHidesRemainder() {
        assertFalse(DiagnosticSecrets.redact("hashes=[\"fixture-one\", {\"x\": \"fixture-two").contains("fixture"))
    }
    @Test fun certificateDigestAndUsefulErrorRemainVisible() {
        val source="TLS handshake rejected; sha256=abcd1234; HTTP 503"
        assertEquals(source,ClientEvents.redact(source))
    }
    @Test fun authorizationAndRepeatedRedactionDoNotLeakSuffixes() {
        val once=ClientEvents.redact("authorization=Bearer fixture-token; password=fixture password with spaces; code=503")
        assertFalse(once.contains("fixture"));assertTrue(once.contains("503"))
        assertEquals(once,ClientEvents.redact(once))
    }
    @Test fun storageProxyAndCamelCaseSecretsHideTheirWholeQuotedValue() {
        listOf("accessKey","secretKey","aws_secret_access_key","aws_access_key_id","socks_pass","pass",
            "credential","signature","x-amz-security-token","privateKey","clashSecret","apiSecret","encryption_key").forEach {key->
            val output=ClientEvents.redact("\"$key\": \"fixture\\\" spaced tail\", code=503")
            assertFalse(key,output.contains("fixture"));assertFalse(key,output.contains("tail"));assertTrue(key,output.contains("503"))
        }
    }
}
