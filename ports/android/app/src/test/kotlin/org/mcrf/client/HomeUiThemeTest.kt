package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class HomeUiThemeTest {
    @Test fun backdropHasVisibleColorSeparationWithoutStrongerNeon() {
        for(dark in listOf(false,true)) {
            val p=HomeUiTheme.palette(dark)
            val separation=listOf(0,8,16).sumOf {shift -> kotlin.math.abs(((p.background ushr shift)and 255)-((p.end ushr shift)and 255))}
            assertTrue("Gradient stops must differ visibly",separation>=40)
        }
        assertEquals(24,HomeUiTheme.glowAlpha(true))
    }
    private fun luminance(color:Int):Double {
        fun channel(shift:Int):Double {
            val c=((color ushr shift) and 255)/255.0
            return if(c<=.04045)c/12.92 else ((c+.055)/1.055).pow(2.4)
        }
        return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
    }
    private fun contrast(a:Int,b:Int):Double {
        val x=luminance(a);val y=luminance(b)
        return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
    }
    @Test fun smallLabelsMeetReadableContrastInBothThemes() {
        for(dark in listOf(false,true)) {
            val p=HomeUiTheme.palette(dark)
            for(background in listOf(p.surface,p.field,p.background,p.end,p.selected))
                for(text in listOf(p.ink,p.muted,p.accent))
                    assertTrue("dark=$dark foreground=${text.toUInt()} background=${background.toUInt()}",contrast(text,background)>=4.5)
            assertTrue(contrast(p.onAccent,p.accent)>=4.5)
        }
    }
    @Test fun requestedHueAndWarmLightSurfacesArePresent() {
        val dark=HomeUiTheme.palette(true);val light=HomeUiTheme.palette(false)
        assertTrue((dark.glow and 255)>((dark.glow ushr 8) and 255))
        assertTrue(((dark.glow ushr 16) and 255)>((dark.glow ushr 8) and 255))
        assertTrue(((light.glow ushr 8) and 255)>((light.glow ushr 16) and 255))
        assertTrue(((light.background ushr 16) and 255)>(light.background and 255))
        assertNotEquals(light.background,light.end);assertNotEquals(dark.background,dark.end)
    }
    @Test fun darkContentStaysNeutralWithRestrainedPurpleDecoration() {
        val p=HomeUiTheme.palette(true)
        fun spread(c:Int):Int {
            val channels=listOf((c ushr 16) and 255,(c ushr 8) and 255,c and 255)
            return channels.max()-channels.min()
        }
        // Large surfaces stay close to charcoal; purple remains an accent.
        for(c in listOf(p.background,p.end,p.surface,p.field,p.line,p.selected))
            assertTrue("surface is too saturated: ${c.toUInt()}",spread(c)<=16)
        assertTrue(spread(p.accent)<=51) // previous accent spread was101
        assertTrue((p.accent and 255)>((p.accent ushr 16) and 255))
        assertTrue(HomeUiTheme.glowAlpha(true)<=24)
        assertTrue(HomeUiTheme.glowAlpha(true,secondary=true)<=14)
        assertTrue((HomeUiTheme.glassEdge(true) ushr 24)<=32)
        assertTrue((HomeUiTheme.ripple(true) ushr 24)<=36)
    }
    @Test fun lightThemeKeepsWarmSurfacesWithTealGradient() {
        assertEquals(HomeUiTheme.Palette(
            0xfff3eadc.toInt(),0xffcfe5dd.toInt(),0xfffffbf2.toInt(),0xffe9dfcf.toInt(),0xffc9bead.toInt(),
            0xff2e2823.toInt(),0xff665b50.toInt(),0xff006467.toInt(),0xff32ddd0.toInt(),0xffd3ebe2.toInt(),0xfffffbf2.toInt()
        ),HomeUiTheme.palette(false))
        assertEquals(40,HomeUiTheme.glowAlpha(false));assertEquals(24,HomeUiTheme.glowAlpha(false,secondary=true))
        assertEquals(0x99fffaf0.toInt(),HomeUiTheme.glassEdge(false))
        assertEquals(0x24006f70,HomeUiTheme.ripple(false))
    }
}
