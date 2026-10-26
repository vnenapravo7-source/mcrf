package org.mcrf.client

/** A service probe follows its actual owner, not the unchanged fallback preference. */
internal object ProbeBackendScope {
    fun usesSteer(configured:String?,ownedSteerRuntime:Boolean,providerDiagnostic:Boolean):Boolean =
        !providerDiagnostic&&(ownedSteerRuntime||configured=="steer")
}
