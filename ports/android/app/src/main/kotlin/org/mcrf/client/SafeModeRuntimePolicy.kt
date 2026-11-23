package org.mcrf.client

/** Enabling the gate cancels only recovery, never a healthy/user-owned connection. */
internal object SafeModeRuntimePolicy {
    private fun cancelRecovery(state:RecoveryState.Snapshot,pending:Boolean,blockingTun:Boolean):Boolean =
        pending||blockingTun||state.trafficBlocked||state.phase in setOf(
            RecoveryState.Phase.WAITING_NETWORK,RecoveryState.Phase.BACKOFF,
            RecoveryState.Phase.RECONNECTING,RecoveryState.Phase.BLOCKED)
    fun apply(enabled:Boolean,state:RecoveryState.Snapshot,pending:Boolean,blockingTun:Boolean,
              cancel:()->Unit,release:()->Unit):Boolean {
        if(!enabled||!cancelRecovery(state,pending,blockingTun))return false
        cancel()
        release()
        return true
    }
}
