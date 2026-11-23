package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class BottomNavigationDragTest {
    @Test fun expandingPaintStaysInsideBothEdgesWithoutChangingFrozenDestinationInRtlOrLtr() {
        val width = 400f
        for (rtl in listOf(false, true)) for (count in 3..4) for (selected in 0 until count) {
            val slots = BottomNavigationGeometry.slots(
                BottomNavigationGeometry.widths(width, count, BottomNavigationStyle.EXPANDING, selected, 64f), rtl)
            for (finger in listOf(1f, 399f)) {
                val drag = BottomNavigationDrag(8f)
                drag.begin(slots[selected].center, 28f, width, 56f, slots)
                drag.move(finger, 28f)
                val destination = slots.indices.first { finger >= slots[it].left && finger < slots[it].left + slots[it].width }
                val raw = drag.x
                for (half in listOf(48f, 66f, 86f)) {
                    val paint = BottomNavigationGeometry.paintCenter(raw, half, width)
                    assertTrue(paint - half >= 0f && paint + half <= width)
                    assertEquals(raw, drag.x, 0f)
                    assertEquals(destination, drag.destination())
                    assertEquals(width - paint, BottomNavigationGeometry.paintCenter(width - raw, half, width), .001f)
                }
                assertEquals(destination, drag.finish())
            }
        }
        assertEquals(334f, BottomNavigationGeometry.paintCenter(356.67f, 66f, width), 0f)
        assertEquals(66f, BottomNavigationGeometry.paintCenter(43.33f, 66f, width), 0f)
    }
    private fun gesture(rtl: Boolean = false) = BottomNavigationDrag(8f).apply {
        begin(if (rtl) 350f else 50f, 30f, 400f, 72f,
            BottomNavigationGeometry.slots(List(4) { 100f }, rtl))
    }
    @Test fun tapAndSlopNeverSelectADragDestination() {
        val drag = gesture(); drag.move(56f, 33f)
        assertEquals(BottomNavigationDrag.State.PENDING, drag.state)
        assertNull(drag.destination())
    }
    @Test fun previewIsContinuousAndReleaseDestinationUsesCurrentFingerNotVelocity() {
        val drag = gesture(); drag.move(185f, 30f)
        assertEquals(185f, drag.x, 0f)
        assertEquals(listOf(0f, .65f, .35f, 0f), drag.weights())
        assertEquals(1, drag.destination())
        drag.move(340f, 30f); drag.move(65f, 30f)
        assertEquals(0, drag.destination())
        assertEquals(.85f, drag.weights()[0], .00001f)
    }
    @Test fun verticalAndDiagonalLockCannotBecomeHorizontalNavigationLater() {
        for (x in listOf(51f, 75f)) {
            val drag = gesture(); drag.move(x, 55f); drag.move(350f, 30f)
            assertEquals(BottomNavigationDrag.State.REJECTED, drag.state)
            assertNull(drag.destination())
        }
    }
    @Test fun LeavingAnyEdgeRejectsEvenIfTheFingerReturns() {
        for ((x, y) in listOf(-1f to 30f, 400f to 30f, 150f to -1f, 150f to 72f)) {
            val drag = gesture(); drag.move(170f, 30f); drag.move(x, y); drag.move(350f, 30f)
            assertNull(drag.destination())
            assertEquals(BottomNavigationDrag.State.REJECTED, drag.state)
        }
    }
    @Test fun cancelAndAdditionalPointerRejectRatherThanCommit() {
        val drag = gesture(); drag.move(300f, 30f); drag.reject()
        assertNull(drag.destination())
        drag.reset(); assertEquals(BottomNavigationDrag.State.IDLE, drag.state)
        assertTrue(drag.centers.isEmpty())
    }
    @Test fun rtlMapsPhysicalCoordinatesToLogicalIdsAndMirrorsWeights() {
        val ltr = gesture(); val rtl = gesture(true)
        for (x in listOf(120f, 185f, 320f, 90f)) {
            ltr.move(x, 30f); rtl.move(400 - x, 30f)
            assertEquals(ltr.destination(), rtl.destination())
            ltr.weights().zip(rtl.weights()).forEach { (a, b) -> assertEquals(a, b, .00001f) }
        }
    }
    @Test fun expandingGeometryIsFrozenWhileSamplingAndWeightsRemainNormalized() {
        val slots = BottomNavigationGeometry.slots(listOf(160f, 80f, 80f, 80f)).toMutableList()
        val drag = BottomNavigationDrag(8f)
        drag.begin(75f, 30f, 400f, 56f, slots)
        slots[1] = BottomNavigationGeometry.Slot(120f, 60f)
        for (x in 90..390) {
            drag.move(x.toFloat(), 30f)
            assertEquals(1f, drag.weights().sum(), .00001f)
            assertTrue(drag.weights().all { it in 0f..1f })
        }
        assertEquals(200f, drag.centers[1], 0f)
    }
    @Test fun boundaryClampsToEndCentersWithoutOvershootAndFreshDownRecovers() {
        val drag = gesture(); drag.move(399f, 30f)
        assertEquals(350f, drag.x, 0f); assertEquals(3, drag.destination())
        drag.reject(); drag.begin(50f, 30f, 400f, 72f, BottomNavigationGeometry.slots(List(2) { 100f }))
        drag.move(130f, 30f); assertEquals(1, drag.destination())
    }
    @Test fun outsideDownEmptyTabsAndNonFiniteInputAreNotDestinations() {
        val drag = BottomNavigationDrag(8f)
        drag.begin(-1f, 30f, 400f, 72f, BottomNavigationGeometry.slots(listOf(100f)))
        assertEquals(BottomNavigationDrag.State.IDLE, drag.state)
        drag.begin(50f, 30f, 400f, 72f, emptyList())
        assertEquals(BottomNavigationDrag.State.IDLE, drag.state)
        val active = gesture(); active.move(Float.NaN, 30f)
        assertNull(active.destination())
    }
    @Test fun unequalExpandingWidthsUseFrozenEdgesRatherThanCenterVoronoiRegions() {
        for (rtl in listOf(false, true)) {
            val slots = BottomNavigationGeometry.slots(listOf(180f, 60f, 60f, 60f), rtl)
            val drag = BottomNavigationDrag(8f)
            drag.begin(slots[0].center, 30f, 360f, 56f, slots)
            // The last 20dp of the wide selected slot are closer to its neighbour's
            // center, but still belong to the wide slot. Mirror both edges in RTL.
            val inside = if (rtl) slots[0].left + 2 else slots[0].left + slots[0].width - 2
            drag.move(inside, 30f); assertEquals("rtl=$rtl", 0, drag.destination())
            val neighbour = if (rtl) slots[0].left - 2 else slots[0].left + slots[0].width + 2
            drag.move(neighbour, 30f); assertEquals("rtl=$rtl", 1, drag.destination())
            for (i in slots.indices) {
                drag.move(slots[i].left + 1, 30f); assertEquals(i, drag.destination())
                drag.move(slots[i].left + slots[i].width - 1, 30f); assertEquals(i, drag.destination())
            }
        }
    }
    @Test fun releaseConsumesDestinationOnceAndClearsTheStream() {
        val drag = gesture(); drag.move(320f, 30f)
        assertEquals(3, drag.finish())
        assertEquals(BottomNavigationDrag.State.IDLE, drag.state)
        assertNull(drag.finish())
        drag.move(150f, 30f)
        assertNull(drag.finish())
        assertTrue(drag.weights().isEmpty())
    }
    @Test fun rejectedAndPendingStreamsCannotCommitOnReleaseAndNewDownRecovers() {
        val drag = gesture(); assertNull(drag.finish())
        drag.begin(50f, 30f, 400f, 56f, BottomNavigationGeometry.slots(List(4) { 100f }))
        drag.move(320f, 30f); drag.reject()
        assertNull(drag.finish())
        drag.begin(50f, 30f, 400f, 56f, BottomNavigationGeometry.slots(List(4) { 100f }))
        drag.move(220f, 30f); assertEquals(2, drag.finish())
    }
    @Test fun exactSharedEdgesBelongToThePhysicalSlotOnTheirRightInBothDirections() {
        for (rtl in listOf(false, true)) {
            val slots = BottomNavigationGeometry.slots(listOf(180f, 60f, 60f, 60f), rtl)
            val drag = BottomNavigationDrag(8f)
            drag.begin(slots[0].center, 30f, 360f, 56f, slots)
            for (index in slots.indices.filter { slots[it].left > 0 }) {
                drag.move(slots[index].left, 30f)
                assertEquals("rtl=$rtl edge=${slots[index].left}", index, drag.destination())
            }
        }
    }
    @Test fun invalidGeometryDoesNotStartAnUnrenderableGesture() {
        val drag = BottomNavigationDrag(8f)
        for ((width, height) in listOf(Float.NaN to 56f, 400f to Float.POSITIVE_INFINITY, 0f to 56f)) {
            drag.begin(50f, 30f, width, height, BottomNavigationGeometry.slots(listOf(100f)))
            assertEquals(BottomNavigationDrag.State.IDLE, drag.state)
        }
        for (slot in listOf(BottomNavigationGeometry.Slot(0f, 0f), BottomNavigationGeometry.Slot(Float.NaN, 100f))) {
            drag.begin(50f, 30f, 400f, 56f, listOf(slot))
            assertEquals(BottomNavigationDrag.State.IDLE, drag.state)
            assertNull(drag.finish())
        }
    }
}
