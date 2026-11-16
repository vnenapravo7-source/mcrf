package org.mcrf.client

/** The final publication gate, not a connectivity test. Capture marks/profile
 * off UI; evaluate volatile AUTO/network/clock at the same lock as publication. */
internal class SteerReadyAdmission(val marked:Boolean,val profileMatches:Boolean,val completedAt:Long,val network:Long) {
    fun ready(automatic:Boolean,currentNetwork:Long?,now:Long):Boolean = automatic&&marked&&profileMatches&&
        currentNetwork==network&&network>0&&completedAt>=0&&now>=completedAt&&now-completedAt<=300000
}
