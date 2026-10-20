package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class Csqtt25EnvelopeTest {
    // Independent Python cryptography AESGCM fixture, nonce0..11, upstream public format key.
    private val encoded="AAECAwQFBgcICQoLiDAk-G_tWw5zdEU6hMH5KxLvy85_74Ka499aqbf3i8LAJU-u9HWCdRYBYQXbLXevMgsHDRME4bvHlEI5KnurmlZqrWbqPXwXzWRje9Ib6WHN9WUHSsTEYE0x5MOaWYEX9uiCyaqoaRDfZv0hNnT6uxbcYa3WOhN2"
    private val plain="csqtt://connect?v=2&host=fixture.invalid&peer=46000&password=fixture-only&hashes=fixture-call-0123456789abcdef&workers=7"
    @Test fun independentKnownAnswerPreservesExactQueryIncludingNonLegacyWorkerCount() {
        val result=Csqtt25Envelope.decode("csqtt://$encoded")
        assertEquals(plain,result.link)
        assertEquals("Csqtt25Envelope(redacted)",result.toString())
    }
    @Test fun standardAndUrlBase64AndWrappedWhitespaceAreAccepted() {
        val standard=Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(encoded))
        assertEquals(plain,Csqtt25Envelope.decode("CSQTT://$standard").link)
        assertEquals(plain,Csqtt25Envelope.decode("csqtt://"+encoded.chunked(32).joinToString("\r\n ")).link)
    }
    @Test fun authenticationFailureNeverFallsBackToPlaintextOrLeaksInput() {
        val bytes=Base64.getUrlDecoder().decode(encoded);bytes[12]=(bytes[12].toInt() xor 1).toByte()
        val bad=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val error=assertThrows(IllegalArgumentException::class.java){Csqtt25Envelope.decode("csqtt://$bad")}
        assertFalse(error.message!!.contains("fixture-only"));assertFalse(error.message!!.contains(bad))
    }
    @Test fun plaintextAndMalformedAndOversizedEnvelopesCannotBeMisidentifiedAs25Ciphertext() {
        for(value in listOf(plain,"csqtt://a","csqtt://"+"A".repeat(65536),"https://$encoded","csqtt://"+encoded+"#fragment"))
            assertThrows(IllegalArgumentException::class.java){Csqtt25Envelope.decode(value)}
    }
}
