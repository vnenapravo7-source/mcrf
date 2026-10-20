package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class WarpAppearanceTest {
    private fun luminance(color: Int): Double = listOf(16,8,0).map { shift ->
        val c=((color shr shift) and 255)/255.0
        if(c<=0.04045) c/12.92 else ((c+0.055)/1.055).pow(2.4)
    }.let {it[0]*0.2126+it[1]*0.7152+it[2]*0.0722}
    @Test fun darkGreenLabelOnItsFixedSurfaceExceedsNormalTextContrastInEitherTheme() {
        val ratio=(luminance(WarpAppearance.SURFACE)+0.05)/(luminance(WarpAppearance.TEXT)+0.05)
        assertTrue("WCAG text contrast $ratio",ratio>=4.5)
        assertEquals("Выбран WARP · AMS",WarpAppearance.selectedText("AMS"))
    }
}
