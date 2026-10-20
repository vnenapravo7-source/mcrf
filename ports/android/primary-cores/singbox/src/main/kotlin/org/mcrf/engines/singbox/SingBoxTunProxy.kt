package org.mcrf.engines.singbox

import org.mcrf.engine.spi.v1.Platform

/** Disabled native HTTP proxy options have no backing value; never call their getters. */
internal object SingBoxTunProxy {
    fun read(enabled: Boolean, server: () -> String, port: () -> Int,
             bypass: () -> List<String>): Platform.HttpProxy =
        if(enabled) Platform.HttpProxy(true,server(),port(),bypass())
        else Platform.HttpProxy(false,"",0,emptyList())
}
