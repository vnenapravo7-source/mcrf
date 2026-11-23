package org.mcrf.engines.singbox

/** Exercises the exact lazy getter guard used by the native TunOptions adapter. */
object SingBoxTunProxyChecks {
    @JvmStatic fun main(args: Array<String>) {
        var checks=0
        fun checkThat(value:Boolean){check(value);checks++}
        val disabled=SingBoxTunProxy.read(false,{error("Disabled native server getter called")},
            {error("Disabled native port getter called")},{error("Disabled native bypass getter called")})
        checkThat(!disabled.enabled)
        checkThat(disabled.server.isEmpty())
        checkThat(disabled.port==0)
        checkThat(disabled.bypass.isEmpty())
        val calls=mutableListOf<String>()
        val enabled=SingBoxTunProxy.read(true,{calls.add("server");"127.0.0.1"},
            {calls.add("port");8080},{calls.add("bypass");listOf("localhost")})
        checkThat(enabled.enabled)
        checkThat(enabled.server=="127.0.0.1" && enabled.port==8080)
        checkThat(enabled.bypass==listOf("localhost"))
        checkThat(calls==listOf("server","port","bypass"))
        println("PASS $checks actual TUN HTTP proxy getter-guard checks; no JNI execution")
    }
}
