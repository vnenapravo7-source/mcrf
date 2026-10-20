package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class SteerAutoSessionTest {
    private val a="a".repeat(64);private val b="b".repeat(64);private val c="c".repeat(64)
    @Test fun measuresWholeCapturedCatalogueButChoosesOnlyMarked(){
        val session=SteerAutoSession(listOf(a,b,c),setOf(b),1)
        assertEquals(listOf(a,b,c),session.measurementIds)
        assertTrue(session.complete(1,a,1,100));assertTrue(session.complete(1,b,40,100));assertTrue(session.complete(1,c,2,100))
        assertEquals(3,session.completed());assertEquals(listOf(b),session.candidates(100))
    }
    @Test fun ignoresUnmarkedUnknownAndLateGenerations(){
        val session=SteerAutoSession(listOf(a),emptySet(),9)
        assertFalse(session.complete(8,a,1,100));assertFalse(session.complete(9,b,1,100))
        assertTrue(session.complete(9,a,1,100));assertTrue(session.candidates(100).isEmpty())
    }
    @Test fun stableLatencyOrderAndNoDuplicateCompletion(){
        val session=SteerAutoSession(listOf(c,b,a),setOf(a,b,c),1)
        session.complete(1,a,2,100);session.complete(1,b,1,100);session.complete(1,c,1,100)
        assertFalse(session.complete(1,b,0,200));assertEquals(listOf(c,b,a),session.candidates(200))
    }
    @Test fun staleFutureAndFailedResultsNeverWin(){
        val session=SteerAutoSession(listOf(a,b,c),setOf(a,b,c),1)
        session.complete(1,a,-1,100);session.complete(1,b,1,101);session.complete(1,c,2,100)
        assertEquals(listOf(c),session.candidates(100));assertEquals(listOf(b,c),session.candidates(300100))
        assertEquals(listOf(b),session.candidates(300101))
        assertTrue(session.candidates(300102).isEmpty())
    }
    @Test fun failureInvalidatesPriorWinnerWithoutChangingOtherResults(){
        val session=SteerAutoSession(listOf(a,b),setOf(a,b),1)
        session.complete(1,a,1,100);session.complete(1,b,2,100)
        assertFalse(session.healthFailed(2,a,101));assertFalse(session.healthFailed(1,a,99))
        assertTrue(session.healthFailed(1,a,101));assertEquals(listOf(b),session.candidates(101))
    }
    @Test fun stopRejectsLateNativeCallbacks(){
        val session=SteerAutoSession(listOf(a),setOf(a),1);session.complete(1,a,1,100);session.stop()
        assertFalse(session.complete(1,a,1,101));assertFalse(session.healthFailed(1,a,102))
        assertEquals(0,session.completed());assertTrue(session.candidates(102).isEmpty())
    }
    @Test fun capturesInputCollectionsInsteadOfMutableUiLists(){
        val ids=mutableListOf(a,b);val marks=mutableSetOf(a)
        val session=SteerAutoSession(ids,marks,1);ids.clear();marks.clear()
        session.complete(1,a,1,100);assertEquals(listOf(a,b),session.measurementIds);assertEquals(listOf(a),session.candidates(100))
    }
    @Test fun rejectsUnboundedOrAmbiguousCapture(){
        for(ids in listOf(emptyList(),listOf(a,a),listOf("token"),(0..256).map {it.toString(16).padStart(64,'0')})){
            try{SteerAutoSession(ids,setOf(a),1);fail("Invalid capture accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
