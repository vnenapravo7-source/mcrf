package org.mcrf.client

/** Native validation remains mandatory in the actual worker before RUN/TUN. */
object ConnectionStartPolicy {
    fun servicePrepares(diagnostic:Boolean,mixedServers:Boolean)=!diagnostic&&!mixedServers
    fun skipTemporarySteerWorker(automatic:Boolean,mixedServers:Boolean)=!automatic&&!mixedServers
}
