package org.mcrf.client

/** The order is shared by both server sections; WARP follows the shared TG and VK actions. */
object ServerToolbarActions {
    enum class Action { CLIPBOARD, QR, PING, REFRESH, VK, TELEGRAM, WARP }
    fun order(whitelists: Boolean, showWarp: Boolean): List<Action> = listOf(
        Action.CLIPBOARD, Action.QR, Action.PING, Action.REFRESH,
        Action.TELEGRAM, Action.VK
    ) + if (showWarp) listOf(Action.WARP) else emptyList()
}

/** Inject live host-owned actions without giving the page ownership of Telegram or navigation. */
data class ServerPageActions(
    val vkSettings: (() -> Unit)? = null,
    val telegramProxy: (() -> Unit)? = null,
    val warp: (() -> Unit)? = null,
    val compareTransports: ((Boolean) -> Unit)? = null
)
