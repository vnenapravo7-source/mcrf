package org.mcrf.client

/** Native validation remains mandatory in the actual worker before RUN/TUN. */
object ConnectionStartPolicy {
    internal class MixedSteerRequiresManualVpn : IllegalArgumentException("Смешанные правила Steer требуют ручного подключения в режиме VPN.")

    fun servicePrepares(diagnostic:Boolean,mixedServers:Boolean)=!diagnostic&&!mixedServers
    fun skipTemporarySteerWorker(automatic:Boolean,mixedServers:Boolean)=!automatic&&!mixedServers

    internal fun requireMixedSteerVpn(vpn:Boolean,automatic:Boolean) {
        if(!vpn||automatic)throw MixedSteerRequiresManualVpn()
    }
}
