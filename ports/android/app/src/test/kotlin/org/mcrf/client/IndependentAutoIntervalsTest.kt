package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class IndependentAutoIntervalsTest {
    @Test fun detectorChangeDoesNotTouchSelectionOrEnableEitherFeature(){
        val state=mutableMapOf("interval" to 15,BsDetectionInterval.KEY to 60)
        val change=AutoCheckIntervals.change(AutoCheckIntervals.Kind.DETECTION,300)
        state[change.first]=change.second
        assertEquals(mapOf("interval" to 15,BsDetectionInterval.KEY to 300),state)
        assertEquals(15,AutoCheckIntervals.seconds(AutoCheckIntervals.Kind.SELECTION){key,default->state[key]?:default})
        assertEquals(300,AutoCheckIntervals.seconds(AutoCheckIntervals.Kind.DETECTION){key,default->state[key]?:default})
    }
    @Test fun selectionChangeDoesNotTouchDetectionAndIndependentValuesSurviveReopen(){
        val state=mutableMapOf("interval" to 60,BsDetectionInterval.KEY to 900)
        val change=AutoCheckIntervals.change(AutoCheckIntervals.Kind.SELECTION,30)
        state[change.first]=change.second
        val reopened=state.toMap()
        assertEquals(900,AutoCheckIntervals.seconds(AutoCheckIntervals.Kind.DETECTION){key,default->reopened[key]?:default})
        assertEquals(30,AutoCheckIntervals.seconds(AutoCheckIntervals.Kind.SELECTION){key,default->reopened[key]?:default})
    }
    @Test fun legacyValuesAndAbsentDefaultsAreReadWithoutMigration(){
        val old=mapOf("interval" to 17,BsDetectionInterval.KEY to 120)
        assertEquals(17,AutoCheckIntervals.seconds(AutoCheckIntervals.Kind.SELECTION){key,default->old[key]?:default})
        assertEquals(120,AutoCheckIntervals.seconds(AutoCheckIntervals.Kind.DETECTION){key,default->old[key]?:default})
        for(kind in AutoCheckIntervals.Kind.values())assertEquals(60,AutoCheckIntervals.seconds(kind){_,default->default})
        assertEquals("17 с",AutoCheckIntervals.label(17))
    }
    @Test fun eachMenuHasItsOwnTitleKeyAndRejectsUnsupportedChoice(){
        assertNotEquals(AutoCheckIntervals.Kind.DETECTION.key,AutoCheckIntervals.Kind.SELECTION.key)
        assertNotEquals(AutoCheckIntervals.Kind.DETECTION.title,AutoCheckIntervals.Kind.SELECTION.title)
        for(kind in AutoCheckIntervals.Kind.values()){
            for(value in BsDetectionInterval.seconds)assertEquals(kind.key to value,AutoCheckIntervals.change(kind,value))
            assertThrows(IllegalArgumentException::class.java){AutoCheckIntervals.change(kind,0)}
        }
    }
}
