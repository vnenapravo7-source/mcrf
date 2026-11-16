package org.mcrf.client

/** Pure test-session admission: a saved BS option is never an ordinary-VPN selector. */
internal object CsqttDirectTunPolicy {
    fun requested(saved:Boolean,vpn:Boolean,explicitBs:Boolean)=saved&&vpn&&explicitBs
    fun reconfigure(saved:Boolean,ownedDirect:Boolean,ownedBs:Boolean)=ownedDirect||saved&&ownedBs
    fun active(owned:Boolean,preparing:Boolean)=owned||preparing
}
