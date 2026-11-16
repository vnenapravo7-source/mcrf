package org.mcrf.client

/** Common gate for WebView navigation/history/error events. Never exposes callback secrets in a result. */
class VkRelayCallback(private val save: (VkRelayProtocol.Authorization) -> Unit) {
    enum class Result { IGNORE, REJECT, SAVED, SAVE_FAILED, ALREADY_SAVED }
    private var saved=false
    private var closed=false
    fun close(){closed=true}
    fun accept(url: String,state: String,oauthStarted: Boolean): Result {
        if(closed)return Result.IGNORE
        if(!oauthStarted || !VkRelayProtocol.isCallback(url)) return Result.IGNORE
        if(saved) return Result.ALREADY_SAVED
        val authorization=VkRelayProtocol.authorization(url,state) ?: return Result.REJECT
        return try { save(authorization);saved=true;Result.SAVED } catch(_: Exception) { Result.SAVE_FAILED }
    }
}
