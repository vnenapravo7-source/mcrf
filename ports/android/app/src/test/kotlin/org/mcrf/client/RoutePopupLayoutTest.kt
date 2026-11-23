package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class RoutePopupLayoutTest {
    private val frame=RoutePopupLayout.Bounds(0,24,400,824)
    @Test fun outputOpensImmediatelyBelowItsAnchorWithRoomAvailable() {
        val anchor=RoutePopupLayout.Bounds(30,180,175,236)
        val popup=RoutePopupLayout.anchored(frame,anchor,196,156,4,12)
        assertEquals(anchor.left,popup.left)
        assertEquals(anchor.bottom+4,popup.top)
        assertEquals(156,popup.height)
    }
    @Test fun outputMovesAboveTheAnchorWhenKeyboardLeavesNoRoomBelow() {
        val anchor=RoutePopupLayout.Bounds(210,700,370,756)
        val popup=RoutePopupLayout.anchored(frame,anchor,196,156,4,12)
        assertEquals(anchor.top-4,popup.bottom)
        assertTrue(popup.right<=frame.right-12)
        assertEquals(156,popup.height)
    }
    @Test fun bothPositionsRespectInsetsAndConstrainedWindows() {
        for(width in listOf(240,320,400,720))for(height in listOf(280,600,900)) {
            val visible=RoutePopupLayout.Bounds(40,48,40+width,48+height)
            val anchor=RoutePopupLayout.Bounds(visible.right-84,visible.top+height/2,visible.right-24,visible.top+height/2+48)
            val dropdown=RoutePopupLayout.anchored(visible,anchor,360,400,4,12)
            val centered=RoutePopupLayout.centered(visible,360,400,12)
            for(popup in listOf(dropdown,centered)) {
                assertTrue(popup.left>=visible.left+12);assertTrue(popup.right<=visible.right-12)
                assertTrue(popup.top>=visible.top);assertTrue(popup.bottom<=visible.bottom)
            }
            assertTrue(kotlin.math.abs((centered.left+centered.right)-(visible.left+visible.right))<=1)
            assertTrue(kotlin.math.abs((centered.top+centered.bottom)-(visible.top+visible.bottom))<=1)
        }
    }
    @Test fun dropdownOffsetsRemainCorrectWithNonzeroDialogOrigin() {
        val anchor=RoutePopupLayout.Bounds(128,284,300,336)
        val screen=RoutePopupLayout.Bounds(128,340,324,496)
        assertEquals(Pair(0,4),RoutePopupLayout.dropDownOffset(screen,anchor))
        val above=RoutePopupLayout.Bounds(128,124,324,280)
        assertEquals(Pair(0,-212),RoutePopupLayout.dropDownOffset(above,anchor))
    }
    @Test fun serverWindowStaysCenteredWhenStrategyOrAutoControlsExpand() {
        for(height in listOf(140,260,420,540)) {
            val popup=RoutePopupLayout.centered(frame,360,height,12)
            assertEquals(200,(popup.left+popup.right)/2)
            assertEquals(424,(popup.top+popup.bottom)/2)
        }
    }
    @Test fun allPolicyCountsFollowLabelEndWithIdenticalGap() {
        val width=144;val padding=4;val gap=2
        for(labelWidth in listOf(42,65,81))for(badgeWidth in listOf(16,22,38)) {
            val badge=RoutePopupLayout.badgeLeft(width,labelWidth,badgeWidth,padding,gap)
            val labelEnd=padding+(width-2*padding-badgeWidth-gap+labelWidth)/2
            assertEquals(gap,badge-labelEnd)
            assertTrue(badge+badgeWidth<=width)
        }
    }
    @Test fun longLabelCannotPushCountOutsideButton() {
        val badge=RoutePopupLayout.badgeLeft(96,200,24,4,2)
        assertTrue(badge>=0);assertTrue(badge+24<=96)
    }
}
