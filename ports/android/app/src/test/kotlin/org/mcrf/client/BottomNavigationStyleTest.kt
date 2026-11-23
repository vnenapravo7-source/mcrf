package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class BottomNavigationStyleTest {
    @Test fun floatingNotchIsOnlyAvailableAtTheBottom() {
        assertFalse(BottomNavigationStyle.FLOATING in BottomNavigationStyle.choicesFor(BottomNavigationStyle.TOP))
        assertEquals(BottomNavigationStyle.EXPANDING, BottomNavigationStyle.resolve(BottomNavigationStyle.FLOATING, BottomNavigationStyle.TOP))
        assertEquals(BottomNavigationStyle.FLOATING, BottomNavigationStyle.resolve(BottomNavigationStyle.FLOATING, BottomNavigationStyle.BOTTOM))
        assertEquals(BottomNavigationStyle.SLIDING, BottomNavigationStyle.resolve(BottomNavigationStyle.SLIDING, BottomNavigationStyle.TOP))
    }
    @Test fun sectionPlacementDefaultsToTopAndSupportsExplicitBottom() {
        assertEquals(BottomNavigationStyle.TOP,BottomNavigationStyle.position(null))
        assertEquals(BottomNavigationStyle.TOP,BottomNavigationStyle.position("future-position"))
        assertEquals(BottomNavigationStyle.BOTTOM,BottomNavigationStyle.position("bottom"))
        assertEquals(BottomNavigationStyle.TOP,BottomNavigationStyle.position("top"))
        assertEquals(listOf("bottom","top"),BottomNavigationStyle.positions.keys.toList())
    }
    @Test fun expandingIsDefaultAndExplicitSavedVariantsArePreserved() {
        assertEquals(BottomNavigationStyle.EXPANDING, BottomNavigationStyle.resolve(null))
        assertEquals(BottomNavigationStyle.EXPANDING, BottomNavigationStyle.resolve("future-style"))
        assertEquals(BottomNavigationStyle.EXPANDING, BottomNavigationStyle.resolve("normal"))
        assertEquals(BottomNavigationStyle.EXPANDING, BottomNavigationStyle.resolve(""))
        BottomNavigationStyle.choices.keys.forEach { assertEquals(it, BottomNavigationStyle.resolve(it)) }
        assertEquals(BottomNavigationStyle.FLOATING, BottomNavigationStyle.resolve("floating-notch"))
        assertEquals(listOf("sliding-pill", "floating-notch", "expanding-label"), BottomNavigationStyle.choices.keys.toList())
        val saved = mapOf(BottomNavigationStyle.KEY to "liquid-bulge", "unrelated" to "keep")
        assertEquals(BottomNavigationStyle.EXPANDING, BottomNavigationStyle.resolve(saved[BottomNavigationStyle.KEY]))
        assertEquals("liquid-bulge", saved[BottomNavigationStyle.KEY])
        assertFalse(BottomNavigationStyle.choices.containsKey("liquid-bulge"))
        assertTrue(BottomNavigationStyle.choices.values.none {it.contains("№") || it.first().isDigit()})
    }
    @Test fun equalSlotsRespectMarginsForAllExistingTabCounts() {
        for (count in 2..4) {
            val centers = (0 until count).map { FloatingNotchMotion.center(320f, it, count, 16f) }
            assertEquals(320f, centers.first() + centers.last(), .001f)
            assertTrue(centers.first() - 24 >= 16)
            assertTrue(centers.last() + 24 <= 304)
            centers.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 48) }
        }
    }
    @Test fun floatingProtrusionOverlaysContentWithoutReservingAnyHeight() {
        val rest = FloatingNotchMotion.CIRCLE / 2f
        assertEquals(rest, FloatingNotchMotion.y(rest, rest, 0f), .001f)
        assertEquals(rest-14, FloatingNotchMotion.y(rest, rest, .5f), .001f)
        assertEquals(rest, FloatingNotchMotion.y(rest, rest, 1f), .001f)
        assertEquals(32, FloatingNotchMotion.CIRCLE)
        assertEquals(56, FloatingNotchMotion.BAR_HEIGHT)
        assertEquals(0, BottomNavigationGeometry.top(BottomNavigationStyle.FLOATING))
        val circleY = BottomNavigationGeometry.top(BottomNavigationStyle.FLOATING) + rest - FloatingNotchMotion.TOP
        assertEquals(-12f, circleY - rest, 0f)
        assertTrue(FloatingNotchMotion.y(circleY, circleY, .5f) - rest < -12f)
        assertEquals(0, BottomNavigationGeometry.top(BottomNavigationStyle.LIQUID))
        for(style in listOf(BottomNavigationStyle.SLIDING,BottomNavigationStyle.EXPANDING))
            assertEquals(0,BottomNavigationGeometry.top(style))
        for(style in BottomNavigationStyle.choices.keys)
            assertEquals(0,BottomNavigationGeometry.top(style,false))
        assertEquals(480L, FloatingNotchMotion.DURATION)
    }
    @Test fun retargetingStartsAtActualAnimatedPosition() {
        val x = FloatingNotchMotion.x(50f, 250f, .4f)
        val y = FloatingNotchMotion.y(36f, 36f, .4f)
        assertEquals(x, FloatingNotchMotion.x(x, 150f, 0f), 0f)
        assertEquals(y, FloatingNotchMotion.y(y, 36f, 0f), 0f)
        assertEquals(150f, FloatingNotchMotion.x(x, 150f, 1f), 0f)
        assertEquals(36f, FloatingNotchMotion.y(y, 36f, 1f), .001f)
        for (step in 0..100) assertTrue(FloatingNotchMotion.y(42f, 36f, step / 100f) <= 42f)
        var interrupted = 36f
        repeat(100) {
            val previous = interrupted
            assertEquals(previous, FloatingNotchMotion.y(previous, 36f, 0f), 0f)
            interrupted = FloatingNotchMotion.y(previous, 36f, .1f)
            assertTrue(interrupted >= 22f)
        }
    }
    @Test fun presentationPreferenceDoesNotAlterPageIdentitiesOrPoolSelection() {
        for (style in BottomNavigationStyle.choices.keys) {
            assertEquals(style, BottomNavigationStyle.resolve(style))
            assertEquals(listOf("Главная", "Белые списки", "Маршрутизация", "Логи", "Настройки"), ConnectionAppearance.tabs(false, true, true))
            assertEquals("Главная", ConnectionAppearance.bottomTarget(true, true))
            assertEquals("Белые списки", ConnectionAppearance.bottomTarget(false, true))
        }
    }
    @Test fun eachStyleFitsNarrowPhonesAndLargeLabelsWithoutStealingTouchArea() {
        for (width in listOf(240f, 320f, 360f, 720f)) for (count in 2..4)
            for (style in BottomNavigationStyle.choices.keys) for (chosen in -1 until count) {
                val widths = BottomNavigationGeometry.widths(width, count, style, chosen, 240f)
                assertEquals(width, widths.sum(), .001f)
                assertTrue(widths.all { it >= 48 })
                val slots = BottomNavigationGeometry.slots(widths)
                assertEquals(0f, slots.first().left, .001f)
                assertEquals(width, slots.last().left + slots.last().width, .001f)
                if (style != BottomNavigationStyle.EXPANDING || chosen == -1)
                    assertTrue(widths.distinct().size == 1)
                else assertTrue(widths.indices.filter { it != chosen }.all { widths[chosen] > widths[it] })
            }
    }
    @Test fun expandingLabelShrinksOtherTabsAndRetargetsWithoutWidthJumps() {
        val from = BottomNavigationGeometry.widths(320f, 4, BottomNavigationStyle.EXPANDING, 0, 120f)
        val to = BottomNavigationGeometry.widths(320f, 4, BottomNavigationStyle.EXPANDING, 3, 120f)
        assertTrue(from[0] > from[1])
        val middle = BottomNavigationGeometry.interpolate(from, to, .37f)
        assertEquals(middle, BottomNavigationGeometry.interpolate(middle, from, 0f))
        assertEquals(from, BottomNavigationGeometry.interpolate(middle, from, 1f))
        for (step in 0..100) {
            val widths = BottomNavigationGeometry.interpolate(from, to, step / 100f)
            assertEquals(320f, widths.sum(), .001f)
            assertTrue(widths.all { it >= 48 })
        }
    }
    @Test fun rtlMirrorsPhysicalSlotsWithoutChangingLogicalTabOrder() {
        val widths = BottomNavigationGeometry.widths(320f, 4, BottomNavigationStyle.EXPANDING, 2, 85f)
        val ltr = BottomNavigationGeometry.slots(widths)
        val rtl = BottomNavigationGeometry.slots(widths, true)
        for (i in widths.indices) {
            assertEquals(ltr[i].width, rtl[i].width, 0f)
            assertEquals(320f, ltr[i].center + rtl[i].center, .001f)
        }
    }
    @Test fun automaticAndImmediateRandomizationUseOnlySupportedStylesAndChangeTheCurrentOne() {
        for (placement in BottomNavigationStyle.positions.keys) for (initial in BottomNavigationStyle.choices.keys + listOf("unknown", "")) {
            val supported = BottomNavigationStyle.choicesFor(placement).keys
            var current = mapOf(BottomNavigationStyle.KEY to BottomNavigationStyle.resolve(initial,placement), BottomNavigationStyle.POSITION to placement)
            val seen = mutableSetOf<String>()
            repeat(64) { seed ->
                val changes = RandomAppearance.changes(current, listOf("a", "b"), kotlin.random.Random(seed))
                val next = changes.getValue(BottomNavigationStyle.KEY)
                assertTrue(next in supported)
                assertNotEquals(current[BottomNavigationStyle.KEY], next)
                seen.add(next); current = current + changes
            }
            assertEquals(supported, seen)
        }
    }
    @Test fun liquidIconsRemainReadableAcrossBothGradientEndsAndUserColors() {
        val colors = listOf("#000000", "#FFFFFF", "#006467", "#B8A3D2", "#707070", "#888888",
            "#FF0000", "#00FF00", "#0000FF", "#FFFF00").map(UserThemeColors::parse)
        for (accent in colors) for (secondary in colors) {
            val gradient = BottomNavigationColors.liquid(accent, secondary)
            assertTrue(UserThemeColors.contrast(gradient.foreground, accent) >= 4.5)
            assertTrue(UserThemeColors.contrast(gradient.foreground, gradient.end) >= 4.5)
        }
    }
    @Test fun softGradientsStayNearSurfaceAndRemainReadableInBothThemes() {
        for(surface in listOf(0xff24222b.toInt(),0xfff5f3f8.toInt()))
            for(accent in listOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffb8a3d2.toInt())) {
                val gradient=BottomNavigationColors.soft(surface,accent,0xff8070a0.toInt())
                for(color in listOf(gradient.start,gradient.end)) {
                    assertTrue(UserThemeColors.contrast(gradient.foreground,color)>=4.5)
                    for(shift in listOf(0,8,16)) assertTrue(kotlin.math.abs(((color ushr shift)and 255)-((surface ushr shift)and 255))<=51)
                }
            }
    }
}
