package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwgInviteSnapshotTest {
    @Test fun successfulEmptyAssignedSetIsAnEmptySnapshot() {
        assertEquals("",SwgInviteSubscription.links(JSONObject("{\"ok\":true,\"data\":{\"peers\":[]}}")))
    }
    @Test fun unknownTargetAbortsInsteadOfSilentlyDroppingPartOfSnapshot() {
        try {SwgInviteSubscription.links(JSONObject("{\"ok\":true,\"data\":{\"peers\":[{\"targets\":[{\"unknown\":{}}]}]}}"));fail()}
        catch(_:IllegalArgumentException){}
    }
    @Test fun missingTargetsIsAnInvalidResponseRatherThanEmptySuccess() {
        try {SwgInviteSubscription.links(JSONObject("{\"ok\":true,\"data\":{\"peers\":[{}]}}"));fail()}
        catch(_:org.json.JSONException){}
    }
}
