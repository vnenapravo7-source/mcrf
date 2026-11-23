package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ServiceTestCatalogTest {
    @Test fun taxonomyHasStableUniqueKeysAndOfficialSourcePin() {
        assertEquals("6f8028ff4fb02566d027f37804cf924f9aeac62f", ServiceTestCatalog.SOURCE_REVISION)
        val categories = ServiceTestCatalog.categories
        val services = categories.flatMap { it.services }
        assertEquals(categories.size, categories.map { it.id }.toSet().size)
        assertEquals(services.size, services.map { it.id }.toSet().size)
        assertEquals(ServiceTestCatalog.targets.size, ServiceTestCatalog.targets.map { it.id }.toSet().size)
        assertTrue(services.map { it.title }.containsAll(listOf("Discord", "YouTube", "Telegram", "Riot и Valorant", "ARC Raiders", "Claude", "Speedtest")))
    }
    @Test fun httpsIsOriginOnlyWithNoSecretsRedirectsRecipesOrCustomPorts() {
        ServiceTestCatalog.targets.forEach { target ->
            target.endpoint?.let {
                assertEquals("https", it.scheme); assertEquals(443, it.port)
                assertNull(it.userInfo); assertNull(it.query); assertNull(it.fragment); assertEquals("/", it.path)
            }
        }
        assertNull(ServiceTestCatalog.find("https://user:secret@host/"))
        for (id in listOf("discord-voice-ip", "youtube-quic", "riot-udp", "registry-bulk")) {
            val target = ServiceTestCatalog.find(id)!!
            assertEquals(DiagnosticCheck.UNSUPPORTED, target.check); assertNull(target.endpoint)
        }
    }
    @Test fun maliciousHostsAreNotEndpoints() {
        for (host in listOf("host/path", "user:secret@example.org", "127.0.0.1", "::1", "example.org?token=secret", "example.org\r\nInjected: yes")) {
            try { ServiceTestTarget("safe-id", "test", host); fail(host) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun searchFindsServicesPartsAndCategoriesWithoutDroppingSelection() {
        val target = ServiceTestCatalog.find("discord-images")!!
        assertTrue(ServiceTestCatalog.matching("кАрТиНкИ").flatMap { it.services }.flatMap { it.parts }.contains(target))
        assertTrue(ServiceTestCatalog.matching("DISCORD").flatMap { it.services }.single().parts.contains(target))
        assertTrue(ServiceTestCatalog.matching("неизвестный-сервис-123").isEmpty())
        val selection = ServiceTestsSelection()
        selection.toggle(listOf(target))
        ServiceTestCatalog.matching("Netflix")
        assertEquals(setOf(target.id), selection.selected)
        selection.toggle(listOf(target)); assertTrue(selection.selected.isEmpty())
    }
    @Test fun partialGroupSelectionPromotesToAllThenClears() {
        val parts = ServiceTestCatalog.categories.flatMap { it.services }.first { it.id == "discord" }.parts
        val selection = ServiceTestsSelection()
        selection.toggle(parts.take(1)); selection.toggle(parts)
        assertEquals(parts.map { it.id }.toSet(), selection.selected)
        selection.toggle(parts); assertTrue(selection.selected.isEmpty())
    }
}
