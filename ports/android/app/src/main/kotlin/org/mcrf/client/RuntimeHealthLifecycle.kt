package org.mcrf.client

/** Serial service lifecycle adapter. Identifiers are process-independent monotonic timestamps. */
internal class RuntimeHealthLifecycle(
    private val nextSession:()->Long,
    private val emit:(String,HealthEvent)->Unit
) {
    private data class Active(val run:Long,val server:String,val session:Long,
                              val network:HealthNetwork,val engine:HealthEngine)
    private var active:Active?=null
    private var recovering:HealthDimension?=null

    @Synchronized fun connected(run:Long,server:String,network:HealthNetwork,engine:HealthEngine) {
        if(server.isBlank() || server.length>1024)return
        if(active?.let {it.run==run&&it.server==server}==true)return
        stopped(HealthDisconnect.UNKNOWN)
        val current=Active(run,server,nextSession(),network,engine)
        active=current
        send(current,HealthEventKind.SESSION_STARTED)
        send(current,HealthEventKind.CONNECTED)
        recovering?.let { send(current,HealthEventKind.RECOVERED,it) }
        recovering=null
    }
    @Synchronized fun failed(reason:RecoveryReason) {
        val dimension=when(reason) {
            RecoveryReason.NETWORK_LOSS->HealthDimension.NETWORK
            RecoveryReason.ENGINE_CRASH->HealthDimension.ENGINE
            else->HealthDimension.SERVER
        }
        active?.let {send(it,HealthEventKind.ERROR,dimension)}
        stopped(when(reason) {
            RecoveryReason.NETWORK_LOSS->HealthDisconnect.NETWORK_LOST
            RecoveryReason.ENGINE_CRASH->HealthDisconnect.ENGINE_STOPPED
            else->HealthDisconnect.ERROR
        })
        recovering=dimension
    }
    @Synchronized fun stopped(reason:HealthDisconnect) {
        active?.let {send(it,HealthEventKind.DISCONNECTED,reason=reason)}
        active=null
        if(reason==HealthDisconnect.USER)recovering=null
    }
    private fun send(a:Active,kind:HealthEventKind,dimension:HealthDimension=HealthDimension.ENGINE,
                     reason:HealthDisconnect=HealthDisconnect.UNKNOWN) {
        emit(a.server,HealthEvent(a.session,kind,dimension,a.network,a.engine,reason))
    }
}
