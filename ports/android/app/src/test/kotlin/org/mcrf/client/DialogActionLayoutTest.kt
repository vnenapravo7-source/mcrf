package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class DialogActionLayoutTest {
    @Test fun twoNormalButtonsReserveWholeRowWithAirAboveAndBelow(){assertEquals(64,DialogActionLayout.requiredHeight(listOf(48,48),false,8,8))}
    @Test fun tallerMultilineLabelCannotBeCroppedToOld48dpFooter(){assertEquals(80,DialogActionLayout.requiredHeight(listOf(48,64),false,8,8))}
    @Test fun narrowStackedActionsReserveBothButtons(){assertEquals(112,DialogActionLayout.requiredHeight(listOf(48,48),true,8,8))}
    @Test fun neutralThirdButtonRemainsReachableWhenStacked(){assertEquals(176,DialogActionLayout.requiredHeight(listOf(48,64,48),true,8,8))}
    @Test fun horizontalThreeButtonRowUsesTallestNotSum(){assertEquals(80,DialogActionLayout.requiredHeight(listOf(48,64,48),false,8,8))}
    @Test fun emptyFooterDoesNotInventAnAction(){assertEquals(0,DialogActionLayout.requiredHeight(emptyList(),false,0,0))}
    @Test fun invalidGeometryRejected(){assertThrows(IllegalArgumentException::class.java){DialogActionLayout.requiredHeight(listOf(-1),false,8,8)}}
}
