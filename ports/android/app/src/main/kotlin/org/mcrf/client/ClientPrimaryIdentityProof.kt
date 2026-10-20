package org.mcrf.client

/** Self-reported only over the authenticated, private, fresh-generation Binder.
 * This proof NEVER authorizes killing a PID: the host must independently verify
 * UID, command line and birth through /proc before any PID-based forced kill. */
internal object ClientPrimaryIdentityProof {
    fun validSelfProof(kind:String?,birth:String?):Boolean =
        kind=="private-binder-self-v1" && birth!=null && birth.matches(Regex("[1-9][0-9]{0,23}"))
    fun parseBirth(stat:String):String {
        require(stat.length in 1..16384 && stat.contains(") ")) {"PRIMARY_WORKER_SELF_IDENTITY"}
        return stat.substringAfterLast(") ").trim().split(Regex(" +")).getOrNull(19)
            ?.takeIf {validSelfProof("private-binder-self-v1",it)}
            ?: throw IllegalArgumentException("PRIMARY_WORKER_SELF_IDENTITY")
    }
}
