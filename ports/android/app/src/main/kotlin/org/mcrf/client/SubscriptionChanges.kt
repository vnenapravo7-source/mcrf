package org.mcrf.client

data class SubscriptionChanges(val added: Int = 0, val updated: Int = 0, val removed: Int = 0, val subscriptions: Int = 0) {
    operator fun plus(other: SubscriptionChanges) = SubscriptionChanges(added + other.added, updated + other.updated, removed + other.removed, subscriptions + other.subscriptions)
    fun summary() = "Подписок: $subscriptions · добавлено: $added · обновлено: $updated · удалено: $removed"
    companion object {
        // IDs hash full links. A name change is an update; changed connection credentials are a new server.
        private fun key(server: ServerImport.Server) = if (server.original.contains("://")) server.original.substringBefore('#') else server.original
        fun between(before: List<ServerImport.Server>, after: List<ServerImport.Server>): SubscriptionChanges {
            val old = before.groupBy(::key); val new = after.groupBy(::key)
            var added = 0; var updated = 0; var removed = 0
            (old.keys + new.keys).forEach { key ->
                val a = old[key].orEmpty().map { it.original }.toMutableList()
                val b = new[key].orEmpty().map { it.original }.toMutableList()
                a.toList().forEach { if (b.remove(it)) a.remove(it) }
                val matched = minOf(a.size, b.size); updated += matched
                removed += a.size - matched; added += b.size - matched
            }
            return SubscriptionChanges(added, updated, removed, 1)
        }
    }
}
