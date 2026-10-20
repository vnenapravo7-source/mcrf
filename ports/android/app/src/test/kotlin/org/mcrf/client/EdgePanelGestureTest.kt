package org.mcrf.client

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mcrf.client.EdgePanelGesture.Action.*

class EdgePanelGestureTest {
    @Test fun swipeOutOfEitherEdgeHidesExpandedControls(){
        assertEquals(HIDE,EdgePanelGesture.finish(false,false,32f,0f,24f))
        assertEquals(HIDE,EdgePanelGesture.finish(true,false,-32f,0f,24f))
    }
    @Test fun swipeIntoPageShowsCollapsedControls(){
        assertEquals(SHOW,EdgePanelGesture.finish(false,true,-32f,0f,24f))
        assertEquals(SHOW,EdgePanelGesture.finish(true,true,32f,0f,24f))
    }
    @Test fun verticalDragStillMovesControls(){
        for(left in listOf(false,true))for(collapsed in listOf(false,true))
            assertEquals(MOVE,EdgePanelGesture.finish(left,collapsed,35f,80f,24f))
    }
    @Test fun expandedControlsCanStillMoveToOtherEdge(){
        assertEquals(MOVE,EdgePanelGesture.finish(false,false,-90f,0f,24f))
        assertEquals(MOVE,EdgePanelGesture.finish(true,false,90f,0f,24f))
    }
    @Test fun tapsAndSmallMotionDoNotHide(){
        for(left in listOf(false,true))for(collapsed in listOf(false,true))
            assertEquals(MOVE,EdgePanelGesture.finish(left,collapsed,12f,1f,24f))
    }
    @Test fun diagonalDragDoesNotAccidentallyHide(){
        assertEquals(MOVE,EdgePanelGesture.finish(false,false,40f,35f,24f))
    }
    @Test fun invalidCoordinatesCannotHide(){
        assertEquals(MOVE,EdgePanelGesture.finish(false,false,Float.NaN,0f,24f))
        assertEquals(MOVE,EdgePanelGesture.finish(false,false,50f,Float.POSITIVE_INFINITY,24f))
    }
}
