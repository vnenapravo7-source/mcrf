package org.mcrf.client

/** No network identifiers, URLs, response bodies or credentials belong in this state. */
enum class CaptivePortalState { UNKNOWN, OFFLINE, UNVALIDATED, INTERNET, PORTAL, POSSIBLE_PORTAL }

object CaptivePortalPolicy {
    const val KEY = "betaCaptivePortal"
    const val PROBE_TTL_MS = 30_000L
    fun system(available: Boolean, portal: Boolean, validated: Boolean) = when {
        !available -> CaptivePortalState.OFFLINE
        portal -> CaptivePortalState.PORTAL
        validated -> CaptivePortalState.INTERNET
        else -> CaptivePortalState.UNVALIDATED
    }
    fun probe(code: Int) = when (code) {
        204 -> CaptivePortalState.INTERNET
        200, in 300..399 -> CaptivePortalState.POSSIBLE_PORTAL
        else -> CaptivePortalState.UNVALIDATED
    }
    fun pause(state: CaptivePortalState) = state in setOf(
        CaptivePortalState.PORTAL, CaptivePortalState.POSSIBLE_PORTAL,
        CaptivePortalState.OFFLINE, CaptivePortalState.UNVALIDATED)
    fun label(state: CaptivePortalState) = when (state) {
        CaptivePortalState.PORTAL -> "Сеть требует входа. Автоподключение приостановлено."
        CaptivePortalState.POSSIBLE_PORTAL -> "Возможно, сеть требует входа. Автоподключение приостановлено."
        CaptivePortalState.OFFLINE -> "Нет сети. Это не ошибка VPN-сервера."
        CaptivePortalState.UNVALIDATED -> "Доступ в интернет не подтверждён. Это не ошибка VPN-сервера."
        CaptivePortalState.INTERNET -> "Доступ в интернет подтверждён."
        CaptivePortalState.UNKNOWN -> "Состояние сети пока неизвестно."
    }
}
