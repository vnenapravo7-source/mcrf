package org.mcrf.client

/** Retained TUN/loopback listeners are not evidence of a working carrier. */
internal object QuickTilePresentation {
    fun active(command:String,status:String,selectedIsBs:Boolean):Boolean =
        command in setOf("vpn","bs")&&ConnectionPresentation.connected(status)&&selectedIsBs==(command=="bs")
}
