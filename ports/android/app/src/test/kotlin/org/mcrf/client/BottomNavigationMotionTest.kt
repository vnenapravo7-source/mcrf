package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class BottomNavigationMotionTest {
    @Test fun tapSpatialWeightsRemainNormalizedThroughTheOldCollapseGapInBothDirections() {
        for (rtl in listOf(false,true)) for (count in 3..4) {
            val from = BottomNavigationGeometry.widths(400f,count,BottomNavigationStyle.EXPANDING,0,120f)
            val to = BottomNavigationGeometry.widths(400f,count,BottomNavigationStyle.EXPANDING,count-1,120f)
            val start = BottomNavigationGeometry.slots(from,rtl).first().center
            val end = BottomNavigationGeometry.slots(to,rtl).last().center
            for (step in 0..100) {
                val t = step/100f
                val travel = BottomNavigationMotion.travel(BottomNavigationStyle.EXPANDING,t)
                val slots = BottomNavigationGeometry.slots(BottomNavigationGeometry.interpolate(from,to,travel),rtl)
                val weights = BottomNavigationGeometry.weightsAt(slots,FloatingNotchMotion.x(start,end,travel))
                assertEquals(1f,weights.sum(),.00001f)
                assertTrue(weights.all { it in 0f..1f })
                assertTrue(BottomNavigationMotion.singleLabel(weights).index >= 0)
                val half = weights.indices.sumOf { i ->
                    val candidate = BottomNavigationGeometry.widths(400f,count,BottomNavigationStyle.EXPANDING,i,120f)
                    (((candidate[i]-8)/2)*weights[i]).toDouble()
                }
                assertTrue("tap pill collapsed at t=$t rtl=$rtl", half > 20)
            }
        }
    }
    @Test fun slidingAndLiquidHaveDifferentCompactRestAndTravelSilhouettes() {
        val pill = BottomNavigationGeometry.compactHighlight(BottomNavigationStyle.SLIDING,0f,1f)
        val blob = BottomNavigationGeometry.compactHighlight(BottomNavigationStyle.LIQUID,0f,1f)
        assertEquals(40f,pill.half*2,0f); assertEquals(40f,pill.height,0f)
        assertEquals(48f,blob.half*2,0f); assertEquals(36f,blob.height,0f)
        for (step in 0..100) {
            val stretch = step/100f
            val sliding = BottomNavigationGeometry.compactHighlight(BottomNavigationStyle.SLIDING,stretch,1f)
            val liquid = BottomNavigationGeometry.compactHighlight(BottomNavigationStyle.LIQUID,stretch,1f)
            val reverse = BottomNavigationGeometry.compactHighlight(BottomNavigationStyle.LIQUID,stretch,-1f)
            assertTrue(sliding.half*2 <= 56f && liquid.half*2 <= 56f)
            assertEquals(0f,sliding.lean,0f); assertEquals(-liquid.lean,reverse.lean,0f)
            assertTrue(28-liquid.height/2 >= 0 && 28+liquid.height/2 <= 56)
        }
    }
    @Test fun movingExpandingTitleFadesBeforeChangingIdentityWithoutCrossOverdraw() {
        for (step in 0..100) {
            val t = step / 100f
            val content = BottomNavigationMotion.singleLabel(listOf(1 - t, t, 0f))
            assertEquals(if (t <= .5f) 0 else 1, content.index)
            assertEquals(kotlin.math.abs(1 - 2 * t), content.opacity, .00001f)
        }
        assertEquals(0f, BottomNavigationMotion.singleLabel(listOf(.5f, .5f)).opacity, 0f)
        assertEquals(-1, BottomNavigationMotion.singleLabel(emptyList()).index)
    }
    @Test fun malformedSystemScaleDoesNotSilentlyDisableAllFourVariants() {
        for (raw in listOf(null, "null", "NaN", "-1", "garbage", "1", "0.5"))
            assertTrue("Unexpected disabled motion: $raw", BottomNavigationMotion.animationsEnabled(raw, false))
        for (raw in listOf("0", "0.0")) assertFalse(BottomNavigationMotion.animationsEnabled(raw, false))
        assertFalse(BottomNavigationMotion.animationsEnabled("1", true))
    }
    @Test fun blockedPageBuildCannotSpendTheTransitionBeforeItsFirstPaint() {
        val clock = BottomNavigationPlayback()
        clock.start(480)
        assertEquals(0f, clock.frame(10_000), 0f) // submit + 2 seconds building the page
        assertEquals(16f / 480, clock.frame(10_016), .0001f)
        val before = clock.progress
        assertEquals(before + 32f / 480, clock.frame(12_000), .0001f) // another heavy render
        clock.pause()
        assertEquals(clock.progress, clock.frame(20_000), 0f) // detach/reattach
        val retained = clock.progress
        assertEquals(retained + 16f / 480, clock.frame(20_016), .0001f)
    }
    @Test fun rapidRetargetAlwaysGetsAVisibleStartAndEventuallySettles() {
        val clock = BottomNavigationPlayback()
        clock.start(440); clock.frame(100); clock.frame(132)
        assertTrue(clock.progress > 0)
        clock.start(440)
        assertEquals(0f, clock.frame(5000), 0f)
        repeat(30) { clock.frame(5016L + it * 16) }
        assertEquals(1f, clock.progress, 0f)
        clock.finish()
        assertEquals(1f, clock.frame(20_000), 0f)
    }
    @Test fun slidingTravelsStretchesAndSettlesWithoutResidualPulse() {
        val s = BottomNavigationStyle.SLIDING
        assertEquals(0f, BottomNavigationMotion.travel(s, 0f), 0f)
        assertTrue(BottomNavigationMotion.travel(s, .45f) in .2f.. .8f)
        assertTrue(BottomNavigationMotion.stretch(.45f) > .99f)
        assertEquals(1f, BottomNavigationMotion.travel(s, 1f), 0f)
        assertEquals(0f, BottomNavigationMotion.stretch(1f), .0001f)
    }
    @Test fun liquidChangesSilhouetteAndMirrorsItsTrailingShoulder() {
        val rest = BottomNavigationMotion.bulge(0f, 1f)
        val middle = BottomNavigationMotion.bulge(.45f, 1f)
        val reverse = BottomNavigationMotion.bulge(.45f, -1f)
        assertEquals(rest, BottomNavigationMotion.bulge(1f, 1f))
        assertTrue(middle.height > rest.height)
        assertTrue(middle.left > middle.right)
        assertEquals(middle.left, reverse.right, .001f)
        assertEquals(middle.right, reverse.left, .001f)
        assertEquals(middle.lean, -reverse.lean, .001f)
    }
    @Test fun floatingChangesIconAtHopApexAndLabelsAfterLanding() {
        assertTrue(FloatingNotchMotion.lift(.5f) >= 14)
        assertEquals(0f, BottomNavigationMotion.iconSwap(.2f), 0f)
        assertTrue(BottomNavigationMotion.iconSwap(.45f) in .1f.. .9f)
        assertEquals(1f, BottomNavigationMotion.iconSwap(.65f), 0f)
        assertEquals(0f, BottomNavigationMotion.oldLabel(.3f), 0f)
        assertEquals(0f, BottomNavigationMotion.newLabel(.6f), 0f)
        assertEquals(1f, BottomNavigationMotion.newLabel(1f), 0f)
    }
    @Test fun expandingNeverExpandsTheNewPillBeforeTheOldOneHasCollapsed() {
        for (step in 0..100) {
            val p = step / 100f
            val old = 1 - BottomNavigationMotion.collapse(p)
            val fresh = BottomNavigationMotion.expand(p)
            assertTrue("Simultaneous pills at $p", old == 0f || fresh == 0f)
        }
        assertEquals(0f, BottomNavigationMotion.expand(.4f), 0f)
        assertEquals(1f, BottomNavigationMotion.collapse(.4f), 0f)
        assertEquals(1f, BottomNavigationMotion.expand(1f), 0f)
    }
}
