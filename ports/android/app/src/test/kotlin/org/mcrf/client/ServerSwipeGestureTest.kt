package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import org.mcrf.client.ServerSwipeGesture.Action.*
import org.mcrf.client.ServerSwipeGesture.Panel

class ServerSwipeGestureTest {
    @Test fun physicalDirectionsMatchTheRequestedActions() {
        assertEquals(SHARE,ServerSwipeGesture.finish(Panel.NONE,100f,5f,64f))
        assertEquals(MANAGE,ServerSwipeGesture.finish(Panel.NONE,-100f,5f,64f))
    }
    @Test fun oppositeSwipeClosesEitherTrayWithoutCallingDeleteOrPing() {
        assertEquals(CLOSE,ServerSwipeGesture.finish(Panel.SHARE,-200f,0f,64f))
        assertEquals(CLOSE,ServerSwipeGesture.finish(Panel.MANAGE,200f,0f,64f))
        assertEquals(SHARE,ServerSwipeGesture.finish(Panel.SHARE,200f,0f,64f))
        assertEquals(MANAGE,ServerSwipeGesture.finish(Panel.MANAGE,-200f,0f,64f))
    }
    @Test fun tapsVerticalAndDiagonalScrollingDoNotTriggerActions() {
        for(panel in Panel.values())for(dx in listOf(-100f,0f,100f)) {
            assertEquals(NONE,ServerSwipeGesture.finish(panel,dx,150f,64f))
            assertEquals(NONE,ServerSwipeGesture.finish(panel,dx,100f,64f))
        }
        assertEquals(NONE,ServerSwipeGesture.finish(Panel.NONE,64f,0f,64f))
        assertFalse(ServerSwipeGesture.horizontal(7f,0f,8f))
        assertTrue(ServerSwipeGesture.horizontal(-9f,1f,8f))
    }
    @Test fun cancellationAndInvalidCoordinatesNeverDeleteOrShare() {
        for(panel in Panel.values())for(dx in listOf(-200f,200f))
            assertEquals(NONE,ServerSwipeGesture.finish(panel,dx,0f,64f,true))
        for(value in listOf(Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY)) {
            assertEquals(NONE,ServerSwipeGesture.finish(Panel.NONE,value,0f,64f))
            assertEquals(NONE,ServerSwipeGesture.finish(Panel.NONE,100f,value,64f))
            assertEquals(NONE,ServerSwipeGesture.finish(Panel.NONE,100f,0f,value))
        }
    }
}
