package org.mcrf.client

import org.mcrf.engine.spi.v1.Platform

/** Private app-process bridge, independent of the engine SPI. Contains metadata only. */
internal interface ObservedDnsMetadata {
    fun observedDns(generation:Long,query:Platform.ConnectionQuery,uid:Int,domain:String,
                    ip:String,ttlMs:Long,observedElapsed:Long)
}
