package org.mcrf.client

import java.util.UUID

/** Reject stale IPC cancel/completion; they cannot affect a later candidate in the same service instance. */
class WarpTraceSession {
    private var id: String?=null
    private var cancelled=false
    private var closed=false
    @Synchronized fun begin(value: String): Boolean {
        require(UUID.fromString(value).toString()==value)
        if(closed || id!=null) return false
        id=value;cancelled=false;return true
    }
    @Synchronized fun activeId(): String?=id
    @Synchronized fun cancel(value: String): Boolean {if(id!=value)return false;cancelled=true;return true}
    @Synchronized fun cancelled(value: String): Boolean=closed || cancelled || id!=value
    @Synchronized fun finish(value: String): Boolean {if(id!=value)return false;id=null;return true}
    @Synchronized fun close() {closed=true;cancelled=true}
}
