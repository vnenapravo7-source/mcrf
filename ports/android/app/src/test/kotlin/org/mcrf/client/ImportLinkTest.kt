package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ImportLinkTest {
    @Test fun nativeLinksRetainEscapesAndLiteralPlusExactly() {
        for (type in listOf("csqtt", "qwdtt", "wdtt", "openflux")) {
            val link = "$type://connect?hashes=one+two%0Athree&pass=fixture%26%2B"
            assertEquals(link, ImportLink.config(link))
        }
    }
    @Test fun webFileAndUnboundedInputsAreNotConfigurationIntents() {
        listOf(null,"https://example.invalid/config","file:///private.json","content://private/item","intent://anything","qwdtt://config?bad=\ncommand","csqtt://"+"x".repeat(65536)).forEach { assertNull(ImportLink.config(it)) }
    }
    @Test fun alternateProtocolSchemesAndOlcOptionsAreAccepted() {
        listOf("wg://fixture@example.invalid:51820", "awg://fixture@example.invalid:51820", "qwdtt:config?peer=fixture", "olcrtc://jitsi?videochannel<video-w=1080>@example.invalid/room#" + "ab".repeat(32)).forEach { assertEquals(it, ImportLink.config(it)) }
        assertNull(ImportLink.config("mcrf://unknown/private"))
    }
}
