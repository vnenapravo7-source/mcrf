package org.mcrf.client

/** Standard web login, not the embedded-app branch. No injected bridge or credential interception. */
object VkRelayWebLogin {
    fun browserAgent(default:String)=default.replace("; wv", "").replace(" Version/4.0", "")
    fun desktopAgent(default:String):String {
        val chrome=Regex("Chrome/[0-9.]+").find(default)?.value?:return browserAgent(default)
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }
}
