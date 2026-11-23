package org.mcrf.client

import org.mcrf.engine.spi.v1.Platform

/** Private same-app bridge for an actual native decision, independent of engine SPI. */
internal interface ObservedRouteMetadata {
    fun observedRoute(generation:Long,query:Platform.ConnectionQuery,uid:Int,domain:String?,tunneled:Boolean)
}
