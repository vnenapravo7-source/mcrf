package org.mcrf.client

/** Provider selection is independent of the route/server. No website launch or fallback. */
internal object SpeedTestProviders {
    data class Provider(val id: String, val label: String, val documentation: String, val unavailable: String? = null) {
        val available get() = unavailable == null
    }
    val all = listOf(
        Provider("cloudflare", "Cloudflare", "https://github.com/cloudflare/speedtest")
    )
    fun find(id: String) = all.firstOrNull { it.id == id }
    fun adapter(id: String): SpeedTestProviderAdapter {
        val provider = requireNotNull(find(id)) { "Unknown measurement provider" }
        check(provider.available) { provider.unavailable.orEmpty() }
        return when (id) {
            "cloudflare" -> CloudflareSpeedTestAdapter
            else -> error("No verified provider adapter")
        }
    }
}

internal interface SpeedTestProviderAdapter {
    val id: String
    val host: String
    fun exchange(socket: java.net.Socket, stage: SpeedTestStage, bytes: Int,
        token: DiagnosticCancellation, progress: (Long) -> Unit): SpeedTestSample
}

private object CloudflareSpeedTestAdapter : SpeedTestProviderAdapter {
    override val id = "cloudflare"
    override val host = SpeedTestPlan.HOST
    override fun exchange(socket: java.net.Socket, stage: SpeedTestStage, bytes: Int,
        token: DiagnosticCancellation, progress: (Long) -> Unit) = SpeedTestHttp.exchange(socket, stage, bytes, token, progress)
}
