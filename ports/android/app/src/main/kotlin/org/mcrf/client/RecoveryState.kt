package org.mcrf.client

/** Pure, synchronized lifecycle owner. Timers carry the explicit START epoch. */
class RecoveryState(private val policy:RecoveryPolicy=RecoveryPolicy()) {
    enum class Phase { IDLE, CONNECTED, WAITING_NETWORK, BACKOFF, RECONNECTING, BLOCKED, FAILED, STOPPED }
    data class Snapshot(
        val epoch:Long=0,val phase:Phase=Phase.IDLE,val userRequested:Boolean=false,
        val reason:RecoveryReason?=null,val attempt:Int=0,val retryInMs:Long?=null,
        val trafficBlocked:Boolean=false
    )
    private var value=Snapshot()
    private var episodeAt:Long?=null
    private var connectedAt:Long?=null
    @Synchronized fun snapshot()=value
    @Synchronized fun start(epoch:Long){value=Snapshot(epoch=epoch,userRequested=true);episodeAt=null;connectedAt=null}
    @Synchronized fun fail(epoch:Long,reason:RecoveryReason,now:Long):Boolean {
        if(epoch!=value.epoch || !value.userRequested || value.phase in setOf(Phase.BLOCKED,Phase.FAILED,Phase.STOPPED))return false
        if(connectedAt?.let {now-it>=policy.stableConnectionMs}==true){episodeAt=null;value=value.copy(attempt=0)}
        connectedAt=null
        if(episodeAt==null)episodeAt=now
        value=value.copy(reason=reason,retryInMs=null,phase=Phase.RECONNECTING)
        return true
    }
    @Synchronized fun schedule(epoch:Long,now:Long,jitter:Double):Long? {
        if(epoch!=value.epoch || !value.userRequested || value.phase!=Phase.RECONNECTING ||
            !policy.retryable(value.reason?:return null) || value.attempt>=policy.maxAttempts)return null
        val delay=policy.delayMs(value.attempt+1,jitter)
        if(now-(episodeAt?:now)+delay>=policy.maxElapsedMs)return null
        value=value.copy(attempt=value.attempt+1,retryInMs=delay,
            phase=if(value.reason==RecoveryReason.NETWORK_LOSS)Phase.WAITING_NETWORK else Phase.BACKOFF)
        return delay
    }
    @Synchronized fun attempt(epoch:Long,now:Long):Boolean {
        if(epoch!=value.epoch || !value.userRequested || value.retryInMs==null ||
            now-(episodeAt?:now)>=policy.maxElapsedMs)return false
        value=value.copy(phase=Phase.RECONNECTING,retryInMs=null)
        return true
    }
    @Synchronized fun connected(epoch:Long,now:Long):Boolean {
        if(epoch!=value.epoch || !value.userRequested || value.phase in setOf(Phase.STOPPED,Phase.BLOCKED,Phase.FAILED))return false
        connectedAt=now;value=value.copy(phase=Phase.CONNECTED,reason=null,retryInMs=null,trafficBlocked=false)
        return true
    }
    @Synchronized fun blocked(epoch:Long,blocked:Boolean) {
        if(epoch==value.epoch)value=value.copy(trafficBlocked=blocked)
    }
    @Synchronized fun finish(epoch:Long,blocked:Boolean) {
        if(epoch==value.epoch)value=value.copy(phase=if(blocked)Phase.BLOCKED else Phase.FAILED,retryInMs=null,trafficBlocked=blocked)
    }
    @Synchronized fun cancel(epoch:Long,reason:RecoveryReason) {
        require(reason in setOf(RecoveryReason.MANUAL_STOP,RecoveryReason.ANDROID_REVOKE,RecoveryReason.CANCELLED))
        value=Snapshot(epoch,Phase.STOPPED,false,reason)
        episodeAt=null;connectedAt=null
    }
}
