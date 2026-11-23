package org.mcrf.client

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsProxyDnsTest {
    private val token get() = DiagnosticCancellation(5000)
    @Test fun mismatchedIdsTruncationAndServfailNeverBecomeSuccess() {
        for (kind in 0..2) {
            try {
                DiagnosticsProxyDns.resolve("discord.media", token) { query ->
                    PacketDnsWire.reply(query, if (kind == 2) 2 else 0, truncated = kind == 1).also { if (kind == 0) it[0] = (it[0].toInt() xor 1).toByte() }
                }
                fail()
            } catch (error: DiagnosticsProxyDns.Failure) {
                assertEquals(DiagnosticDnsFailure.CONTROL_RESPONSE, error.kind)
                if (kind == 2) assertEquals(2, error.rcode)
            }
        }
    }
    @Test fun emptyNoerrorIsNotAddressResolutionSuccess() {
        val addresses = DiagnosticsProxyDns.resolve("discord.media", token) { PacketDnsWire.reply(it, 0) }
        assertTrue(addresses.isEmpty())
    }
    @Test fun matchingQuestionWithUnrelatedAnswerOwnerCannotProduceSuccess() {
        val query = PacketDnsWire.query("discord.media", 1, 7)
        val reply = PacketDnsWire.reply(query, 0, listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))))
        val answerOffset = PacketDnsWire.question(reply, true).end
        // Compression points at the second label, so the owner is "media".
        reply[answerOffset + 1] = 20
        assertTrue(PacketDnsWire.resolvedAddresses(query, reply).isEmpty())
    }
    @Test fun validCnameChainResolvesButCyclesAreRejected() {
        val query = PacketDnsWire.query("discord.media", 1, 7)
        val reply = PacketDnsWire.reply(query, 0).copyOf().apply { this[7] = 2 }
        val alias = byteArrayOf(4) + "edge".toByteArray() + byteArrayOf(0xc0.toByte(), 12)
        fun record(owner: ByteArray, type: Int, data: ByteArray) = owner + byteArrayOf(0, type.toByte(), 0, 1, 0, 0, 0, 60, 0, data.size.toByte()) + data
        val cname = record(byteArrayOf(0xc0.toByte(), 12), 5, alias)
        val address = record(alias, 1, byteArrayOf(1, 2, 3, 4))
        assertEquals("1.2.3.4", PacketDnsWire.resolvedAddresses(query, reply + cname + address).single().hostAddress)
        val cycle = record(byteArrayOf(0xc0.toByte(), 12), 5, byteArrayOf(0xc0.toByte(), 12))
        val one = PacketDnsWire.reply(query, 0).apply { this[7] = 1 }
        try { PacketDnsWire.resolvedAddresses(query, one + cycle); fail() } catch (_: IllegalArgumentException) { }
    }
}
