package org.mcrf.client

/** A completed coordinator attempt cannot receive callbacks into a later attempt.
 * close also waits for an already admitted callback before global progress is cleared.
 * This only owns callback admission, never native resources or cleanup acknowledgement.
 */
internal class ProbeReplyWindow {
    private var open=true
    @Synchronized fun deliver(reply:()->Unit):Boolean {
        if(!open)return false
        reply()
        return true
    }
    @Synchronized fun close(){open=false}
}
