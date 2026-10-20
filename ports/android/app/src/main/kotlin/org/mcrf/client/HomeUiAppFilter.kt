package org.mcrf.client

import java.util.Locale

/** Keep selected system packages removable even with the system filter off. */
object HomeUiAppFilter {
    fun matches(id: String, label: String, system: Boolean, selected: Boolean, query: String, showSystem: Boolean): Boolean {
        val term = query.trim().lowercase(Locale.ROOT)
        return (showSystem || !system || selected) &&
            (id.lowercase(Locale.ROOT).contains(term) || label.lowercase(Locale.ROOT).contains(term))
    }
}
