package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class TrafficJournalRulesTest {
    private val now = 1_000_000L
    private fun entry(domain: String? = null, ip: String = "8.8.8.8",
                      source: TrafficJournalStore.DomainSource = TrafficJournalStore.DomainSource.SNI) =
        TrafficJournalStore.Entry(now, 42, "org.example.client", domain, ip, 443,
            TrafficJournalStore.Protocol.TCP, TrafficJournalStore.Route.VPN, TrafficJournalStore.Result.ALLOWED,
            packages = listOf("org.example.client"), domainSource = source)

    @Test fun exactDomainsArePreferredWithoutAddingSharedIpOrWildcards() {
        val event = entry("youtube.com")
        val draft = TrafficJournalRules.draft(listOf(event, event), now)
        assertEquals("DOMAIN,youtube.com", draft.getString("text"))
        val parsed = RouteRules.parse(draft.getString("text"))
        assertEquals(listOf("youtube.com"), parsed.getValue("domain"))
        assertTrue(parsed.getValue("domain_suffix").isEmpty())
        assertTrue(parsed.getValue("ip_cidr").isEmpty())
        assertEquals("addresses", draft.getString("mode"))
        assertFalse(draft.has("invert"))
        assertFalse(draft.has("nativeMatch"))
    }

    @Test fun unknownDomainUsesOneHostAndIpv6NeverExpandsToSubnet() {
        val draft = TrafficJournalRules.draft(listOf(entry(), entry(ip = "2606:4700:4700::1111")), now)
        val addresses = RouteRules.parse(draft.getString("text")).getValue("ip_cidr")
        assertTrue(addresses.contains("8.8.8.8/32"))
        assertTrue(addresses.single { ':' in it }.endsWith("/128"))
        assertEquals(2, addresses.size)
    }

    @Test fun untrustedPrivateDnsOnlyExpiredAndFutureRowsCannotGenerateRules() {
        val rejected = listOf(
            entry("secret.internal"), entry("https://youtube.com/path"), entry("masked.host.com"),
            entry(ip = "192.168.1.1"), entry(ip = "host.com"),
            entry("youtube.com").copy(event = "dns-answer", domainSource = TrafficJournalStore.DomainSource.DNS_ANSWER),
            entry().copy(timestamp = now - TrafficJournalStore.RETENTION_MS - 1), entry().copy(timestamp = now + 1)
        )
        for (event in rejected) assertThrows(IllegalArgumentException::class.java) {
            TrafficJournalRules.draft(listOf(event), now)
        }
        assertEquals("DOMAIN,youtube.com", TrafficJournalRules.draft(rejected + entry("youtube.com"), now).getString("text"))
    }

    @Test fun draftDoesNotMutateEntriesAndSelectionIdentitySurvivesReload() {
        val event = entry("youtube.com")
        val before = event.copy(packages = event.packages.toList())
        assertEquals(TrafficJournalRules.key(event), TrafficJournalRules.key(before))
        assertNotEquals(TrafficJournalRules.key(event), TrafficJournalRules.key(event.copy(timestamp = now + 1)))
        TrafficJournalRules.draft(listOf(event), now)
        assertEquals(before, event)
        assertThrows(IllegalArgumentException::class.java) { TrafficJournalRules.draft(emptyList(), now) }
    }
}
