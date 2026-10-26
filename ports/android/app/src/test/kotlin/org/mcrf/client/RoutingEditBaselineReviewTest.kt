package org.mcrf.client

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutingEditBaselineReviewTest {
    @Test fun revertingToOpeningSourcePersistsAfterSuccessfulSave() {
        val opened=JSONObject().put("source","first")
        val baseline=RoutingEditBaseline(opened);var current=JSONObject(opened.toString())
        fun save(value:JSONObject)=baseline.commit(value){previous,edited->current=RoutingDefaultsEdits.merge(current,previous,edited)}
        save(JSONObject().put("source","second"));assertEquals("second",current.getString("source"))
        save(opened);assertEquals("first",current.getString("source"))
    }
    @Test fun failedWriteDoesNotAcknowledgeUnsavedEdits() {
        val opened=JSONObject().put("source","first");val baseline=RoutingEditBaseline(opened)
        try {baseline.commit(JSONObject().put("source","second")){_,_->throw IOException()};fail("Must fail")}
        catch(_:IOException) { }
        baseline.commit(JSONObject().put("source","second")){previous,edited->
            assertEquals("first",previous.getString("source"));assertEquals("second",edited.getString("source"))
        }
    }
    @Test fun unrelatedConcurrentSettingsSurviveBothDirections() {
        val opened=JSONObject().put("source","first").put("selectors","old")
        var current=JSONObject(opened.toString()).put("selectors","external")
        val baseline=RoutingEditBaseline(opened)
        for(source in listOf("second","first"))baseline.commit(JSONObject(opened.toString()).put("source",source)){previous,edited->
            current=RoutingDefaultsEdits.merge(current,previous,edited)
        }
        assertEquals("first",current.getString("source"));assertEquals("external",current.getString("selectors"))
    }
    @Test fun callerOrSaveCallbackCannotMutateAcknowledgedBaseline() {
        val initial=JSONObject().put("source","first");val baseline=RoutingEditBaseline(initial)
        initial.put("source","outside")
        val next=JSONObject().put("source","second")
        baseline.commit(next){previous,edited->assertEquals("first",previous.getString("source"));edited.put("source","callback")}
        next.put("source","outside")
        baseline.commit(JSONObject().put("source","third")){previous,_->assertEquals("second",previous.getString("source"))}
    }
}
