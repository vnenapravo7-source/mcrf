package org.mcrf.client

/** Distinguish Home/Overview from app-owned pickers, consent and configuration changes. */
internal class RecentsDeparture {
    private var userLeaving=false
    private var externalLaunch=false
    private var backgroundPause=false
    fun resumed(){userLeaving=false;externalLaunch=false;backgroundPause=false}
    fun paused(){backgroundPause=true}
    fun launched(){externalLaunch=true;userLeaving=false}
    fun launchFailed(){externalLaunch=false}
    fun userLeave(){if(!externalLaunch)userLeaving=true}
    fun removeUi(hidden:Boolean,changingConfiguration:Boolean,finishing:Boolean,multiWindow:Boolean)=
        hidden&&(userLeaving||backgroundPause)&&!externalLaunch&&!changingConfiguration&&!finishing&&!multiWindow
}
