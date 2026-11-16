package org.mcrf.client

/** Never run the scanner through its own VPN route; native Android binding requires a physical handle. */
object WarpNetworkPolicy {
    data class Candidate(val handle: Long, val internet: Boolean, val notVpn: Boolean, val validated: Boolean, val active: Boolean)
    fun select(candidates: List<Candidate>): Long = candidates
        .filter { it.handle != 0L && it.internet && it.notVpn }
        .sortedWith(compareByDescending<Candidate> { it.active }.thenByDescending { it.validated })
        .firstOrNull()?.handle ?: throw IllegalArgumentException("Нет физической сети для регистрации и подбора WARP. Подключись к Wi-Fi или мобильной сети.")
}
