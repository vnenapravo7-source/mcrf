package org.mcrf.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring invariants not covered by the pure geometry/motion tests. */
class BottomNavigationContractTest {
    private fun source(name: String) = listOf(File("src/main/kotlin/org/mcrf/client/$name.kt"),
        File("app/src/main/kotlin/org/mcrf/client/$name.kt")).first { it.isFile }.readText()

    @Test fun hostRetainsOneRendererAndUpdatesVpnBsWithoutRebuildingTheShell() {
        val host = source("MainActivity")
        assertTrue(host.contains("bottomNavigation?:BottomNavigationView(this,::icon)"))
        assertTrue(host.contains("if(shell.getChildAt(index)!==bottomNavigation)shell.removeViewAt(index)"))
        assertTrue(host.contains("if(nav.parent!==shell)shell.addView(nav,"))
        assertFalse(host.contains("val compactNavigation="))
        val partial = host.substringAfter("private fun switchConnectionSection(").substringBefore("private fun updateBottomNavigation(")
        assertTrue(partial.contains("updateBottomNavigation()"))
        assertFalse(partial.contains("shell.removeAllViews()"))
        val update = host.substringAfter("private fun visibleNavigationTabs(").substringBefore("private fun serverCard(")
        assertTrue(update.contains("ConnectionAppearance.tabs("))
        assertTrue(update.contains("ClientSettingsNavigation.visible("))
        assertTrue(update.contains("ConnectionAppearance.bottomTarget("))
        assertTrue(update.contains("navigate(ClientSettingsNavigation.bottomTarget(tab,prefs.getString(ClientSettingsNavigation.ROUTES_PAGE_KEY,null)))"))
        assertTrue(host.contains("state.putString(\"connectionPage\",lastConnectionPage)"))
    }
    @Test fun styleIsSavedInAppearanceAndReadByTheExistingRandomizer() {
        val editor = source("AppearanceSettingsUi")
        assertTrue(editor.contains("prefs.edit().putString(key,entries[index].key).apply()"))
        assertTrue(editor.contains("BottomNavigationStyle.KEY,BottomNavigationStyle.choicesFor(position)"))
        assertTrue(editor.contains("\"bottom-navigation-style\""))
        val random = source("UserThemeColorsUi")
        assertTrue(random.contains("BottomNavigationStyle.KEY,BottomNavigationStyle.POSITION)"))
        assertTrue(random.contains("BottomNavigationStyle.resolve(current[BottomNavigationStyle.KEY],current[BottomNavigationStyle.POSITION])"))
        assertTrue(random.contains("if(!RandomAppearance.foreground.enter("))
    }
    @Test fun rendererRespectsMotionAccessibilityAndDoesNotConsumeInsetsTwice() {
        val renderer = source("BottomNavigationView")
        assertTrue(renderer.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(renderer.contains("\"remove_animations\""))
        assertTrue(renderer.contains("!animationsEnabled()"))
        assertTrue(renderer.contains("playback.start(BottomNavigationMotion.duration(style))"))
        assertTrue(renderer.contains("playback.frame(SystemClock.uptimeMillis())"))
        assertFalse(renderer.contains("ValueAnimator.ofFloat"))
        assertTrue(renderer.contains("cell.contentDescription = tab.description"))
        assertTrue(renderer.contains("info.isSelected = host.isSelected"))
        assertTrue(renderer.contains("TextUtils.TruncateAt.END"))
        assertFalse(renderer.contains("setOnApplyWindowInsetsListener"))
        assertTrue(source("MainActivity").contains("max(bars.bottom, keyboard.bottom)"))
    }
    @Test fun compactRendererUsesPaintedBoundsForPressedStateWithoutAnOutline() {
        val renderer=source("BottomNavigationView")
        assertTrue(renderer.contains("val wanted = dp(barTop.toInt() + FloatingNotchMotion.BAR_HEIGHT)"))
        assertFalse(renderer.contains("FloatingNotchMotion.BAR_HEIGHT + 8"))
        assertTrue(renderer.contains("defaultFocusHighlightEnabled = false"))
        assertFalse(renderer.contains("RippleDrawable"))
        assertTrue(renderer.contains("cell.background = null"))
        assertTrue(renderer.contains("drawable.setBounds(dp(-11), dp(-11), dp(11), dp(11))"))
        assertTrue(renderer.contains("BottomNavigationColors.soft(Loft.SURFACE"))
        assertTrue(renderer.contains("path.addRect(bar, Path.Direction.CW)"))
        assertFalse(renderer.contains("path.addRoundRect(bar"))
        assertFalse(renderer.contains("setStroke("))
    }
    @Test fun localFillsAreRoundedLiquidHasNoBulgeAndDragPaintPrecedesChildDispatch() {
        val renderer = source("BottomNavigationView")
        val region = renderer.substringAfter("private fun drawRegion(").substringBefore("private fun drawIcon(")
        assertTrue(region.contains("Paint.Style.FILL"))
        assertTrue(region.contains("canvas.drawRoundRect("))
        assertFalse(region.contains("canvas.drawRect("))
        assertFalse(renderer.contains("Path.Op.UNION"))
        val dispatch = renderer.substringAfter("override fun dispatchTouchEvent(").substringBefore("override fun onInterceptTouchEvent(")
        assertTrue(dispatch.contains("if (quickSettingsTracking) return super.dispatchTouchEvent(event)"))
        assertTrue(dispatch.indexOf("previewDrag()") in 0 until dispatch.lastIndexOf("super.dispatchTouchEvent(event)"))
        assertTrue(renderer.contains("private val movingContent get() = settlingDrag || drag.state == BottomNavigationDrag.State.DRAGGING"))
        assertTrue(renderer.contains("style == BottomNavigationStyle.EXPANDING && playback.progress < 1f"))
        val start = renderer.indexOf("contentClip.reset()")
        val end = renderer.indexOf("return@forEachIndexed", start)
        assertTrue(start >= 0 && end > start)
        val content = renderer.substring(start,end)
        assertTrue(renderer.contains("i == content.index && content.opacity > 0f"))
        assertEquals(1, Regex("drawLabel\\(").findAll(content).count())
        assertTrue(content.contains("canvas.clipPath(contentClip)"))
        assertTrue(content.contains("contentClip.addRoundRect("))
        assertTrue(content.indexOf("canvas.clipPath(contentClip)") < content.indexOf("val fitted = TextUtils.ellipsize"))
    }
    @Test fun expandingMovingAndSettledFillUseTheSameActualPillGradientBounds() {
        val renderer = source("BottomNavigationView")
        assertTrue(renderer.contains("if(!amoled)paint.shader=expandingGradient(paintCenter-half,paintCenter+half)"))
        assertTrue(renderer.contains("if(!amoled)paint.shader=expandingGradient(slot.center-half,slot.center+half)"))
        val gradient = renderer.substringAfter("private fun expandingGradient(").substringBefore("private fun rebuildCells(")
        assertTrue(gradient.contains("px(left), px(barTop+8), px(right), px(barTop+48)"))
        assertTrue(gradient.contains("regionColors.start, regionColors.end"))
    }
}
