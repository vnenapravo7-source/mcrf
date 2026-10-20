package org.mcrf.client

/** Two user choices; legacy automatic implementations remain intact until explicitly changed. */
object CarrierCaptchaMode {
    fun automatic(wire: String) = wire != "wv"
    fun select(wire: String, automatic: Boolean): String {
        require(wire in setOf("auto", "wv", "rjs")) { "Неизвестный режим капчи." }
        return if (automatic == this.automatic(wire)) wire else if (automatic) "auto" else "wv"
    }
}
