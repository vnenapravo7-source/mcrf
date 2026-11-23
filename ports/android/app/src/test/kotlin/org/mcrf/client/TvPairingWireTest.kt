package org.mcrf.client

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPairingWireTest {
    @Test fun matchingPinTransfersExactProfile() {
        val profile = "ss://example.invalid#Тест"
        val (sent, received) = transfer("031415", "031415", profile)
        assertTrue(sent.isSuccess)
        assertEquals(profile, received.getOrThrow())
    }

    @Test fun editedVlessImportsWithIndependentCredentialsAndTransport() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        val initial = "vless://$uuid@origin.example:443?security=tls&sni=front.example&type=ws&path=%2Ffirst#TV"
        val phone = ConnectionCatalogue("").apply { add(initial) }
        val entry = phone.entries().single()
        val saved = ConnectionEdits.snapshot(entry).put("name", "Мой телевизор · VLESS")
        saved.getJSONObject("outbound").put("server", "edited.example")
        phone.edit(entry.id, saved)
        val outbound = phone.entries().single().outbound ?: error("Missing saved VLESS outbound")
        val share = TvTransferredProfile.capture(phone.entries().single())
        val (_, received) = transfer("000421", "000421", share.encode())
        val transferred = TvTransferredProfile.decode(requireNotNull(received.getOrThrow()))
        val catalogue = ConnectionCatalogue("").apply { add(initial); saveTvProfile(transferred) }
        val tv = ConnectionCatalogue(catalogue.serialize()).entries().single()
        assertEquals("Мой телевизор · VLESS", tv.name)
        assertEquals(initial, tv.original)
        val restored = tv.outbound ?: error("Missing restored VLESS outbound")
        assertEquals("edited.example", tv.host)
        assertEquals(443, tv.port)
        assertEquals(uuid, restored.getString("uuid"))
        assertEquals(outbound.getJSONObject("tls").toString(), restored.getJSONObject("tls").toString())
        assertEquals(outbound.getJSONObject("transport").toString(), restored.getJSONObject("transport").toString())
    }

    @Test fun wrongPinProducesNoProfile() {
        val (sent, received) = transfer("031415", "031416", "fixture")
        assertTrue(sent.isFailure)
        assertTrue(received.isFailure)
    }

    private fun transfer(senderPin: String, receiverPin: String, profile: String): Pair<Result<String?>, Result<String?>> {
        val pool = Executors.newSingleThreadExecutor()
        val loopback = InetAddress.getLoopbackAddress()
        return try {
            ServerSocket(0, 1, loopback).use { server ->
                server.soTimeout = 5000
                val receiver = pool.submit<Result<String?>> {
                    runCatching {
                        server.accept().use { TvPairingWire.exchange(it, receiverPin, null) }
                    }
                }
                val sender = runCatching {
                    Socket(loopback, server.localPort).use { TvPairingWire.exchange(it, senderPin, profile) }
                }
                sender to receiver.get(10, TimeUnit.SECONDS)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
