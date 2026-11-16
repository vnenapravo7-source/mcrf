package org.mcrf.client

/** Internal Beta only. This does not configure or promise Android Always-on lockdown. */
object KillSwitchPolicy {
    const val DEFAULT_ENABLED=false
    fun hold(enabled:Boolean,userRequested:Boolean,vpn:Boolean,reason:RecoveryReason)=
        enabled && userRequested && vpn && reason !in setOf(
            RecoveryReason.MANUAL_STOP,RecoveryReason.ANDROID_REVOKE,RecoveryReason.CANCELLED)
}
