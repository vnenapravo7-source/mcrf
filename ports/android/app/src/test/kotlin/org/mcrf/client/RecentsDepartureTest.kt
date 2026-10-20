package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class RecentsDepartureTest {
    private fun RecentsDeparture.shouldRemove(hidden:Boolean=true,config:Boolean=false,finishing:Boolean=false,multi:Boolean=false)=removeUi(hidden,config,finishing,multi)
    @Test fun deliberateHomeOrOverviewRemovesHiddenUi(){val policy=RecentsDeparture();policy.userLeave();assertTrue(policy.shouldRemove())}
    @Test fun explicitOffNeverRemovesUi(){val policy=RecentsDeparture();policy.userLeave();assertFalse(policy.shouldRemove(hidden=false))}
    @Test fun focusLossWithoutBackgroundPauseDoesNotRemoveUi(){assertFalse(RecentsDeparture().shouldRemove())}
    @Test fun samsungPauseWithoutLeaveHintRemovesHiddenUi(){val policy=RecentsDeparture();policy.paused();assertTrue(policy.shouldRemove())}
    @Test fun pauseStillProtectsAppLaunchedPicker(){val policy=RecentsDeparture();policy.launched();policy.paused();assertFalse(policy.shouldRemove())}
    @Test fun resumedCancelsPendingPrivacyRemoval(){val policy=RecentsDeparture();policy.paused();policy.resumed();assertFalse(policy.shouldRemove())}
    @Test fun pauseProtectsRotationAndMultiWindow(){val policy=RecentsDeparture();policy.paused();assertFalse(policy.shouldRemove(config=true));assertFalse(policy.shouldRemove(multi=true))}
    @Test fun appLaunchedPickerOrConsentIsProtected(){val policy=RecentsDeparture();policy.launched();policy.userLeave();assertFalse(policy.shouldRemove())}
    @Test fun failedExternalLaunchDoesNotDisableNextUserLeave(){val policy=RecentsDeparture();policy.launched();policy.launchFailed();policy.userLeave();assertTrue(policy.shouldRemove())}
    @Test fun rotationIsProtected(){val policy=RecentsDeparture();policy.userLeave();assertFalse(policy.shouldRemove(config=true))}
    @Test fun multiWindowIsProtected(){val policy=RecentsDeparture();policy.userLeave();assertFalse(policy.shouldRemove(multi=true))}
    @Test fun alreadyFinishingIsNotFinishedTwice(){val policy=RecentsDeparture();policy.userLeave();assertFalse(policy.shouldRemove(finishing=true))}
    @Test fun resumeClearsPreviousLeaveAndExternalLaunch(){val policy=RecentsDeparture();policy.userLeave();policy.resumed();assertFalse(policy.shouldRemove());policy.launched();policy.resumed();policy.userLeave();assertTrue(policy.shouldRemove())}
}
