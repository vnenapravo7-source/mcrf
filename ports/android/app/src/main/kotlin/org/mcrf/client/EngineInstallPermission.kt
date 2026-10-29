package org.mcrf.client

internal object EngineInstallPermission {
    /** onResume alone is not a return until Settings has actually paused the owner. */
    class ReturnGate {
        private var waiting=false
        private var away=false
        fun launched() {waiting=true;away=false}
        fun paused() {if(waiting)away=true}
        fun consume(fromResume:Boolean):Boolean {
            if(!waiting||(fromResume&&!away))return false
            waiting=false;away=false
            return true
        }
    }
    fun beforeOfferPhase(phase:String,granted:Boolean)=
        if(phase=="download-ready"&&!granted)"permission-required" else phase

    fun resumePhase(granted:Boolean,hasArchive:Boolean)=when {
        !granted->"permission-denied"
        hasArchive->"install-ready"
        else->"download-ready"
    }
}
