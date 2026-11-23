package org.mcrf.client

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class SurfacePaletteTest {
    @Test fun approvedChromaticStopsAndAlphasAreExactInBothThemes() {
        val surface = 0x80203040.toInt()
        val accent = 0x40a0b0c0
        val glow = 0x20e0d0c0
        assertEquals(SurfacePalette.Stops(0xd6253a4e.toInt(),0xcc23374a.toInt(),0xe03d3c3b.toInt()),
            SurfacePalette.stops(surface,accent,glow,true))
        assertEquals(SurfacePalette.Stops(0xe63c4c5c.toInt(),0xdb344454.toInt(),0xeb46505a.toInt()),
            SurfacePalette.stops(surface,accent,glow,false))
        for (dark in listOf(false,true)) {
            val normal = SurfacePalette.stops(surface,accent,glow,dark)
            val connection = SurfacePalette.stops(surface,accent,glow,dark,connection=true)
            for ((card,panel) in colors(normal).zip(colors(connection))) {
                assertEquals(179,panel ushr 24)
                assertEquals(card and 0xffffff,panel and 0xffffff)
            }
        }
    }

    @Test fun channelBoundsAndRequestedAlphaHoldForTransparentAndSaturatedUserColors() {
        val colors = listOf(0,0x00ffffff,0xffff0000.toInt(),0xff00ff00.toInt(),
            0xff0000ff.toInt(),0xffffffff.toInt(),0xff000000.toInt())
        for (surface in colors) for (accent in colors) for (glow in colors) {
            for (dark in listOf(false,true)) {
                val stops = SurfacePalette.stops(surface,accent,glow,dark)
                for ((stop,target) in listOf(stops.start to accent,stops.middle to accent,stops.end to glow)) {
                    for (shift in listOf(0,8,16)) {
                        val a = (surface ushr shift) and 255
                        val channel = (target ushr shift) and 255
                        val b = if(dark)(channel*.5f).roundToInt()else channel
                        assertTrue(((stop ushr shift) and 255) in minOf(a,b)..maxOf(a,b))
                    }
                }
                assertEquals(if(dark)214 else 230,stops.start ushr 24)
                assertEquals(if(dark)204 else 219,stops.middle ushr 24)
                assertEquals(if(dark)224 else 235,stops.end ushr 24)
            }
        }
        assertEquals(0x00123456,SurfacePalette.withAlpha(0xff123456.toInt(),-1))
        assertEquals(0xff123456.toInt(),SurfacePalette.withAlpha(0x00123456,300))
    }

    @Test fun stableCacheReusesItsValueAndEveryInputInvalidatesIt() {
        var creations = 0
        val cache = SurfacePalette.Cache<Any> { _,_,_,_,_ -> creations++; Any() }
        var left = 10f; var top = 20f; var right = 110f; var bottom = 220f
        var surface = 0xff203040.toInt(); var accent = 0xffa0b0c0.toInt()
        var glow = 0xffe0d0c0.toInt(); var dark = false; var connection = false
        fun get() = cache.value(left,top,right,bottom,surface,accent,glow,dark,connection)
        var previous = get()
        assertNotNull(previous)
        repeat(20) { assertSame(previous,get()) }
        assertEquals(1,creations)
        val changes: List<() -> Unit> = listOf(
            { left += .25f },{ top += .25f },{ right += .25f },{ bottom += .25f },
            { surface = surface xor 1 },{ accent = accent xor 1 },{ glow = glow xor 1 },
            { dark = true },{ connection = true }
        )
        for ((index,change) in changes.withIndex()) {
            change()
            val next = get()
            assertNotSame(previous,next)
            assertSame(next,get())
            assertEquals(index+2,creations)
            previous = next
        }
        val independent = SurfacePalette.Cache<Any> { _,_,_,_,_ -> Any() }
        assertNotSame(previous,independent.value(left,top,right,bottom,surface,accent,glow,dark,connection))
    }

    @Test fun cacheKeepsExactOffsetBoundsAndRejectsEmptyOrNonFiniteBounds() {
        var creations = 0
        var captured = emptyList<Float>()
        val cache = SurfacePalette.Cache<Any> { l,t,r,b,_ ->
            creations++; captured = listOf(l,t,r,b); Any()
        }
        fun get(l:Float,t:Float,r:Float,b:Float) = cache.value(l,t,r,b,0,0,0,false)
        val value = get(-50.25f,12.5f,80.75f,200.5f)
        assertEquals(listOf(-50.25f,12.5f,80.75f,200.5f),captured)
        assertNull(get(0f,0f,0f,1f))
        assertNull(get(0f,0f,1f,0f))
        assertNull(get(2f,0f,1f,1f))
        assertNull(get(0f,2f,1f,1f))
        for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            assertNull(get(bad,0f,1f,1f)); assertNull(get(0f,bad,1f,1f))
            assertNull(get(0f,0f,bad,1f)); assertNull(get(0f,0f,1f,bad))
        }
        assertSame(value,get(-50.25f,12.5f,80.75f,200.5f))
        assertEquals(1,creations)
        assertNotSame(value,get(-49.25f,13.5f,81.75f,201.5f))
        assertEquals(2,creations)
    }

    @Test fun defaultPaletteCompositesKeepTextAndIconContrastWithoutChangingForegrounds() {
        val failures = mutableListOf<String>()
        for (dark in listOf(false,true)) {
            val theme = HomeUiTheme.palette(dark)
            val backdropBases = listOf(theme.background,theme.end,blend(theme.background,theme.end,.58f))
            val backdrops = backdropBases.flatMap { base ->
                val primary = SurfacePalette.withAlpha(theme.glow,HomeUiTheme.glowAlpha(dark))
                val secondary = SurfacePalette.withAlpha(theme.glow,HomeUiTheme.glowAlpha(dark,secondary=true))
                listOf(base,over(primary,base),over(secondary,base),over(primary,over(secondary,base)))
            }
            for (connection in listOf(false,true)) {
                val stops = SurfacePalette.stops(theme.surface,theme.accent,theme.glow,dark,connection)
                val checks = listOf(Triple("ink text",theme.ink,4.5),Triple("muted text",theme.muted,4.5),
                    Triple("accent icon",theme.accent,3.0),Triple("muted icon",theme.muted,3.0))
                for ((name,foreground,minimum) in checks) {
                    var worst = Double.POSITIVE_INFINITY
                    var worstSurface = 0; var worstBackdrop = 0; var position = 0f
                    for (step in 0..100) {
                        val fraction = step/100f
                        val surface = sample(stops,fraction)
                        for (backdrop in backdrops) {
                            val painted = over(surface,backdrop)
                            val ratio = contrast(over(foreground,painted),painted)
                            if (ratio < worst) {
                                worst = ratio; worstSurface = surface; worstBackdrop = backdrop; position = fraction
                            }
                        }
                    }
                    if (worst < minimum) {
                        val alpha = ((worstSurface ushr 24)..255).firstOrNull { candidate ->
                            val painted = over(SurfacePalette.withAlpha(worstSurface,candidate),worstBackdrop)
                            contrast(over(foreground,painted),painted) >= minimum
                        }
                        failures += String.format(Locale.ROOT,
                            "%s %s %s: %.6f:1 < %.1f:1 at %.2f, surface=%08x backdrop=%08x; %s",
                            if(dark)"dark" else "light",if(connection)"connection" else "card/nav",name,
                            worst,minimum,position,worstSurface,worstBackdrop,
                            if(alpha==null)"no alpha-only fix for this sample; ask Main/Terra for a blend adjustment"
                            else "minimum alpha for this sample: $alpha/255 (proposal only; review entire gradient)")
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }

    @Test fun compositionChecksUseThePaintedBackgroundRatherThanOpaqueStopRgb() {
        assertEquals(0xff808080.toInt(),over(0x80ffffff.toInt(),0xff000000.toInt()))
        assertEquals(0xff123456.toInt(),over(0x00ffffff,0xff123456.toInt()))
        assertEquals(21.0,contrast(0xff000000.toInt(),0xffffffff.toInt()),.00001)
        val stops = SurfacePalette.Stops(0x80ff0000.toInt(),0x8000ff00.toInt(),0x800000ff.toInt())
        assertEquals(stops.start,sample(stops,0f))
        assertEquals(stops.middle,sample(stops,.5f))
        assertEquals(stops.end,sample(stops,1f))
        assertEquals(0x80808000.toInt(),sample(stops,.25f))
    }

    private fun colors(stops:SurfacePalette.Stops) = listOf(stops.start,stops.middle,stops.end)

    private fun blend(first:Int,second:Int,fraction:Float):Int {
        fun channel(shift:Int):Int {
            val a = (first ushr shift) and 255; val b = (second ushr shift) and 255
            return (a+(b-a)*fraction).roundToInt() shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }

    /** Premultiplied interpolation, followed by source-over on an opaque backdrop. */
    private fun sample(stops:SurfacePalette.Stops,fraction:Float):Int {
        val first = if(fraction<=.5f)stops.start else stops.middle
        val second = if(fraction<=.5f)stops.middle else stops.end
        val t = if(fraction<=.5f)fraction*2 else (fraction-.5f)*2
        val a = (first ushr 24).toFloat(); val b = (second ushr 24).toFloat()
        val alpha = a+(b-a)*t
        fun channel(shift:Int):Int {
            val premultiplied = ((first ushr shift)and 255)*a*(1-t)+((second ushr shift)and 255)*b*t
            return (if(alpha==0f)0 else (premultiplied/alpha).roundToInt()) shl shift
        }
        return (alpha.roundToInt() shl 24) or channel(16) or channel(8) or channel(0)
    }

    private fun over(foreground:Int,background:Int):Int {
        val alpha = (foreground ushr 24)/255.0
        fun channel(shift:Int) = ((((foreground ushr shift)and 255)*alpha+
            ((background ushr shift)and 255)*(1-alpha)).roundToInt() shl shift)
        return 0xff000000.toInt() or channel(16) or channel(8) or channel(0)
    }

    private fun contrast(first:Int,second:Int):Double {
        fun luminance(color:Int):Double {
            fun linear(shift:Int):Double {
                val c = ((color ushr shift)and 255)/255.0
                return if(c<=.04045)c/12.92 else ((c+.055)/1.055).pow(2.4)
            }
            return .2126*linear(16)+.7152*linear(8)+.0722*linear(0)
        }
        val a = luminance(first); val b = luminance(second)
        return (maxOf(a,b)+.05)/(minOf(a,b)+.05)
    }
}
