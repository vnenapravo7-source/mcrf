package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject

/** Upstream core releases, signed carrier scripts and Android modules are different units. */
object EngineUpdatePolicy {
    const val UPSTREAM = "p1neappleXpress/OpenFlux"
    data class Revision(val version: String, val commit: String) {
        init { require(numbers(version) != null && commit.matches(Regex("[0-9a-f]{40}"))) }
    }
    private fun numbers(value: String): List<Int>? = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
        .matchEntire(value)?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }
    fun compare(left: String, right: String): Int {
        val a = requireNotNull(numbers(left)); val b = requireNotNull(numbers(right))
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }
    fun stableTag(releases: JSONArray): String = (0 until releases.length()).map { releases.getJSONObject(it) }
        .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") && numbers(it.optString("tag_name")) != null }
        .map { it.getString("tag_name") }.maxWithOrNull { a, b -> compare(a, b) }
        ?: throw IllegalArgumentException("В оригинальном репозитории нет стабильного выпуска OpenFlux.")
    fun needsUpdate(installed: Revision?, latest: Revision): Boolean = installed == null ||
        compare(latest.version, installed.version) > 0 || compare(latest.version, installed.version) == 0 && latest.commit != installed.commit
    fun source(metadata: JSONObject): Revision {
        require(metadata.getString("repository") == UPSTREAM) { "Пакет собран из другого репозитория OpenFlux." }
        return Revision(metadata.getString("version"), metadata.getString("commit"))
    }
    enum class Phase { IDLE, CHECKING, CURRENT, UNPUBLISHED, AVAILABLE, DOWNLOADING, QUEUED, CONSENT, INSTALLING, INSTALLED, ERROR }
    fun readyPhase(active: Boolean) = if (active) Phase.QUEUED else Phase.CONSENT
    const val DEFAULT_AUTOMATIC = true
    fun automaticEligible(saved: Boolean?, installed: Boolean) = installed && (saved ?: DEFAULT_AUTOMATIC)
    const val SCRIPT_LIMITATION = "Подписанные .flux — обновления транспортов внутри OpenFlux. Текущий stdio-модуль MCRF не предоставляет их установку/проверку ключа. Нативный APK OpenFlux не заменяет этот модуль."
    const val ROLLBACK_LIMITATION = "Предыдущий APK можно сохранить для ручного восстановления. Android обычно запрещает установку меньшего versionCode: может потребоваться удалить только модуль и установить сохранённый APK. Автоматический откат не гарантируется."
}
