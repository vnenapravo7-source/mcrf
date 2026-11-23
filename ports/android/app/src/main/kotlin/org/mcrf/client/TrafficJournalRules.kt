package org.mcrf.client

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Detached drafts only: no recording, DNS, journal writes or automatic routing changes. */
internal object TrafficJournalRules {
    fun key(entry: TrafficJournalStore.Entry): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(entry.toString().toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return String(CharArray(hash.size * 2) { index ->
            val byte = hash[index / 2].toInt() and 255
            hex[if (index % 2 == 0) byte ushr 4 else byte and 15]
        })
    }

    fun draft(entries: List<TrafficJournalStore.Entry>, now: Long = System.currentTimeMillis()): JSONObject {
        val conditions = linkedSetOf<String>()
        for (entry in entries.take(TrafficJournalStore.MAX_COUNT)) {
            if (entry.event != "connection" || entry.timestamp !in (now - TrafficJournalStore.RETENTION_MS)..now) continue
            val ip = RuleSuggestions.publicIp(entry.ip) ?: continue
            val domain = entry.domain?.let(RuleSuggestions::publicDomain)
            // A hidden/private hostname must not reappear via an IP fallback.
            if (entry.domain != null && domain == null) continue
            if (domain != null && entry.domainSource !in setOf(TrafficJournalStore.DomainSource.UNKNOWN, TrafficJournalStore.DomainSource.DNS_ANSWER))
                conditions.add("DOMAIN,$domain")
            else conditions.add("$ip/${if (':' in ip) 128 else 32}")
        }
        require(conditions.isNotEmpty()) { "Нет подходящих адресов для правила." }
        val text = conditions.joinToString("\n")
        RouteRules.parse(text) // Exact domains and host-only CIDRs; no wildcard/subnet expansion.
        return JSONObject().put("id", UUID.randomUUID().toString().replace("-", ""))
            .put("name", "Из записи трафика").put("text", text)
            .put("mode", "addresses").put("target", "proxy").put("enabled", true)
    }
}
