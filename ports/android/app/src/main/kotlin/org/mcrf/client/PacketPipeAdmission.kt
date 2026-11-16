package org.mcrf.client

import org.mcrf.engine.protocol.pipe.v1.PacketPipeBackend

/** Exact declaration only, no capability inferred from main/module version. */
internal object PacketPipeAdmission {
    fun admitted(value:Any?):Boolean {
        if(value==null)return false
        require(value is String&&value==PacketPipeBackend.CAPABILITY){"PACKET_PIPE_UNKNOWN_CAPABILITY"}
        return true
    }
}
