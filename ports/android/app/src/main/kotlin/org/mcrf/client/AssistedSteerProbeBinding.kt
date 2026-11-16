package org.mcrf.client

/** Volatile only: binds original catalogue content to this exact owned ingress.
 * A ranking result from a retired helper cannot admit a newly started helper. */
internal class AssistedSteerProbeBinding(val logicalFingerprint:String,private val effectiveProfile:String) {
    init {require(logicalFingerprint.matches(Regex("[0-9a-f]{64}"))&&effectiveProfile.isNotEmpty())}
    fun matches(logical:ServerImport.Server,profile:String):Boolean =
        CompactProtocolPolicy.eligible(logical)&&SteerProbePlan.fingerprint(logical)==logicalFingerprint&&profile==effectiveProfile
}
