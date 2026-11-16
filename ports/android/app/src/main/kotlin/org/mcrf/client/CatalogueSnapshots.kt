package org.mcrf.client

import java.util.Collections
import org.json.JSONObject

/** Prepared once per load/commit. The catalogue is never mutated after publication. */
internal class CatalogueSnapshot(val catalogue: ConnectionCatalogue) {
    val entries: List<ServerImport.Server> = Collections.unmodifiableList(catalogue.entries())
    val ids: Set<String> = Collections.unmodifiableSet(entries.mapTo(linkedSetOf()) { it.id })
    val autoIds: Set<String> = Collections.unmodifiableSet(catalogue.autoIds(ids))

    fun sameStructure(other: CatalogueSnapshot): Boolean {
        fun structure(snapshot: CatalogueSnapshot) = JSONObject(snapshot.catalogue.serialize()).apply {
            remove("probes"); remove("autoServers"); remove(CsqttEngineMigration.KEY)
        }
        return RoutingDefaultsEdits.equivalent(structure(this), structure(other))
    }
}

/** Readers never acquire the writer/storage lock. Failed preparation keeps the last good view. */
internal class CatalogueSnapshots {
    @Volatile var current: CatalogueSnapshot? = null
        private set

    fun replace(prepare: () -> CatalogueSnapshot): CatalogueSnapshot = prepare().also { current = it }
    fun readable(): CatalogueSnapshot = current
        ?: throw IllegalStateException("Не удалось прочитать подключения. Исходные данные сохранены.")
}
