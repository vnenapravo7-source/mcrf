package org.mcrf.client

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** All geometry is drawn in a 160 × 72 viewport. Only state changes schedule frames. */
internal class MotionStyleToggle(private val style: String, gradientTrack: Boolean = false) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF(4f, 2f, 156f, 70f)
    private val clip = Path().apply { addRoundRect(track, 35f, 35f, Path.Direction.CW) }
    private val classicGradient = if (style == ConnectionAppearance.TOGGLE && gradientTrack) {
        LinearGradient(4f, 2f, 156f, 70f, HomeUiTheme.gradientColors(), null, Shader.TileMode.CLAMP)
    } else null
    private val shape = Path()
    private val arc = RectF()
    private val dotMoon = arrayOf(
        "0011000", "0110001", "1100011", "1100001", "1100000", "0110010", "0011100",
    )
    private val dotSun = arrayOf(
        "0001000", "0100010", "0011100", "1011101", "0011100", "0100010", "0001000",
    )
    private val matrixAmoledTrack = LinearGradient(10f, 8f, 150f, 64f,
        intArrayOf(0xff201037.toInt(), 0xff10081e.toInt(), 0xff0a0412.toInt()), null, Shader.TileMode.CLAMP)
    private val sky = LinearGradient(0f, 5f, 0f, 67f, 0xff1b2848.toInt(), 0xff080b19.toInt(), Shader.TileMode.CLAMP)
    private val sunset = glow(60f, 36f, 41f, 0xffffef91.toInt(), 0xaaff6b14.toInt())
    private val solarBody = RadialGradient(56f, 31f, 31f,
        intArrayOf(0xffffffeb.toInt(), 0xffffdb58.toInt(), 0xffff8e1b.toInt()),
        floatArrayOf(.05f, .58f, 1f), Shader.TileMode.CLAMP)
    private val corona = glow(126f, 36f, 37f, 0xfff0f8ff.toInt(), 0xc078b1ff.toInt())
    private val reactorGlow by lazy {
        glow(0f,0f,28f,withAlpha(Loft.ACCENT,.58f),withAlpha(Loft.ACCENT,.22f))
    }

    fun draw(canvas: Canvas, width: Int, height: Int, position: Float, failed: Boolean, enabled: Boolean, focused: Boolean, pressed: Boolean) {
        val scale = min(width / 160f, height / 72f) * if (style == ConnectionAppearance.ARC_REACTOR) .90f else 1f
        if (scale <= 0f) return
        val initial = canvas.save()
        canvas.translate((width - 160f * scale) / 2, (height - 72f * scale) / 2)
        canvas.scale(scale, scale)
        if (!enabled) canvas.saveLayerAlpha(0f, 0f, 160f, 72f, 150)
        val clipped = canvas.save()
        canvas.clipPath(clip)
        when (style) {
            ConnectionAppearance.DOT_MATRIX -> drawMatrix(canvas, position)
            ConnectionAppearance.ARC_REACTOR -> drawReactor(canvas, position)
            ConnectionAppearance.ECLIPSE -> drawEclipse(canvas, position)
            else -> drawClassic(canvas, position)
        }
        if (pressed) fillRound(canvas, track, 35f, 0x26ffffff)
        canvas.restoreToCount(clipped)
        val outline = when {
            failed -> Loft.ERROR
            focused -> Loft.ACCENT
            style == ConnectionAppearance.ARC_REACTOR -> Loft.mix(Loft.LINE,Loft.ACCENT,.19f + .15f * position)
            classicGradient != null -> 0xff554564.toInt()
            else -> 0xff65718a.toInt()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (focused || failed) 3f else 1.5f
        paint.color = outline
        canvas.drawRoundRect(track, 35f, 35f, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(initial)
    }

    private fun drawClassic(canvas: Canvas, position: Float) {
        val gradient = classicGradient
        if (gradient == null) fillRound(canvas, track, 35f, Loft.mix(Loft.LINE, Loft.ACCENT, position))
        else {
            shade(canvas, gradient)
            if (position > 0f) fillRound(canvas, track, 35f, withAlpha(Loft.ACCENT, position * .48f))
        }
        circle(canvas, 38f + 84f * position, 36f, 27f, Loft.mix(Loft.INK, Loft.onAccent(), position))
    }

    private fun drawMatrix(canvas: Canvas, position: Float) {
        val transition = 1f - abs(2 * position - 1)
        val amoled = HomeUiTheme.amoled
        if (amoled) shade(canvas, matrixAmoledTrack)
        else fillRound(canvas, track, 35f, Loft.mix(0xff161618.toInt(), 0xff52328a.toInt(), position))
        arc.set(10f, 8f, 150f, 64f)
        val edge = if (amoled) Loft.mix(0xff2a193d.toInt(), 0xff563c75.toInt(), position)
            else Loft.mix(0xff070709.toInt(), 0xff7651a7.toInt(), position)
        fillRound(canvas, arc, 28f, edge)
        arc.inset(1.6f, 2.4f)
        if (amoled) shade(canvas, matrixAmoledTrack, arc, 26f)
        else fillRound(canvas, arc, 26f, Loft.mix(0xff09090b.toInt(), 0xff2b1946.toInt(), position))
        val x = 36f + 88f * position
        circle(canvas, x + 1f, 39f, 26f, 0x55151b29)
        val rim = Loft.mix(0xffa8bdff.toInt(), 0xffffba59.toInt(), position)
        circle(canvas, x, 36f, 26f, Loft.mix(rim, 0xff854ab5.toInt(), transition * .6f))
        circle(canvas, x, 36f, 24.3f, Loft.mix(0xff24262e.toInt(), 0xff512e83.toInt(), position))
        circle(canvas, x - 5f, 29f, 17f, Loft.mix(0x183b99fe, 0x38d7b6ff, position))
        val dot = Loft.mix(0xfff0f1ff.toInt(), 0xfffff1df.toInt(), position)
        for (row in dotMoon.indices) {
            for (col in dotMoon[row].indices) {
                val moon = dotMoon[row][col] == '1'
                val sun = dotSun[row][col] == '1'
                if (!moon && !sun) continue
                val opacity = if (moon && sun) 1f else if (moon) 1f - position else position
                val jitter = ((col * 3 + row * 5) % 7 - 3) * transition
                circle(canvas, x + (col - 3) * 4.3f + jitter, 36f + (row - 3) * 4.3f - jitter * .65f,
                    1.2f, withAlpha(dot, opacity * (1f - transition * .6f)))
            }
        }
        if (transition > .08f) {
            val dawn = ((position - .40f) / .21f).coerceIn(0f, 1f) * transition
            for (row in 0..5) for (col in 0..10) {
                val dx = col - 5
                val dy = row - 5
                if (row == 5 || dx * dx + dy * dy < 13 && row > 1) {
                    circle(canvas, x + dx * 2.35f, 39f + dy * 2.4f, .67f, withAlpha(0xffffb476.toInt(), dawn))
                }
            }
        }
    }

    private fun drawReactor(canvas: Canvas, position: Float) {
        val plate=Loft.mix(Loft.SURFACE,Loft.FIELD,.55f)
        val accent=Loft.ACCENT
        val core=Loft.mix(Loft.SURFACE,Loft.INK,.18f)
        fillRound(canvas,track,35f,plate)
        arc.set(10f,6f,150f,66f)
        fillRound(canvas,arc,30f,Loft.mix(plate,Loft.LINE,.75f))
        arc.inset(1.5f,1.5f)
        fillRound(canvas,arc,27f,Loft.mix(Loft.SURFACE,accent,if(Loft.dark) .06f else .04f))
        for(index in 0..4) {
            val left=34f+19f*index
            val opening=5f*position
            for(side in 0..1) {
                val y=if(side==0)12f else 37f+opening*2f
                arc.set(left,y,left+17f,y+23f-opening*2f)
                fillRound(canvas,arc,2f,Loft.mix(plate,accent,.13f+.13f*position))
            }
        }
        for(x in SCREWS) {
            circle(canvas,x,36f,2.4f,Loft.mix(Loft.LINE,Loft.MUTED,.32f))
        }
        val x=37f+86f*position
        if(position>0f) {
            line(canvas,18f,36f,142f,36f,5f,withAlpha(accent,position*.23f))
            line(canvas,18f,36f,142f,36f,1f,withAlpha(accent,position*.54f))
            val saved=canvas.save()
            canvas.translate(x,36f)
            paint.shader=reactorGlow
            paint.alpha=(position*205).toInt()
            canvas.drawCircle(0f,0f,28f,paint)
            paint.shader=null
            paint.alpha=255
            canvas.restoreToCount(saved)
        }
        circle(canvas,x,36f,24f,Loft.mix(core,accent,.14f+.38f*position))
        circle(canvas,x,36f,21.5f,core)
        arc.set(x-16f,20f,x+16f,52f)
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=3f
        for(index in 0..5) {
            val charge=(position*6f-index).coerceIn(0f,1f)
            paint.color=Loft.mix(Loft.mix(core,Loft.INK,.5f),accent,.78f*charge)
            canvas.drawArc(arc,index*60f-90f,42f,false,paint)
        }
        paint.style=Paint.Style.FILL
        circle(canvas,x,36f,7f,Loft.mix(Loft.mix(core,Loft.INK,.62f),accent,.88f*position))
    }

    private fun drawEclipse(canvas: Canvas, position: Float) {
        shade(canvas, sky)
        for (index in 0..21) {
            val x = 14f + index * 39 % 133
            val y = 10f + index * 17 % 52
            circle(canvas, x, y, if (index % 5 == 0) 1.3f else .7f, if (index % 3 == 0) 0xffb6cbef.toInt() else 0x75c6d9ff)
        }
        val warm = (1f - position * 1.3f).coerceIn(0f, 1f)
        paint.shader = sunset
        paint.alpha = (warm * 255).toInt()
        canvas.drawCircle(60f, 36f, 41f, paint)
        paint.shader = null
        paint.alpha = 255
        if (warm > 0f) {
            for (index in 0..11) {
                val dx = RAY_X[index * 3]
                val dy = RAY_Y[index * 3]
                line(canvas, 60f + 24f * dx, 36f + 24f * dy, 60f + 31f * dx, 36f + 31f * dy,
                    1.3f, withAlpha(0xffffc768.toInt(), warm * .8f))
            }
            paint.shader = solarBody
            paint.alpha = (warm * 255).toInt()
            canvas.drawCircle(60f, 36f, 23f, paint)
            paint.shader = null
            paint.alpha = 255
        }
        val cool = ((position - .45f) / .55f).coerceIn(0f, 1f)
        paint.shader = corona
        paint.alpha = (cool * 255).toInt()
        canvas.drawCircle(126f, 36f, 37f, paint)
        paint.shader = null
        paint.alpha = 255
        if (cool > 0f) {
            circle(canvas, 126f, 36f, 25f, withAlpha(0xffd9e9ff.toInt(), cool))
            for (index in 0..35) {
                val dx = RAY_X[index]
                val dy = RAY_Y[index]
                val outer = 26f + index % 6
                line(canvas, 126f + 24f * dx, 36f + 24f * dy, 126f + outer * dx, 36f + outer * dy,
                    .7f, withAlpha(0xffd2e4ff.toInt(), cool * .76f))
            }
        }
        // The moon crosses the sun once, then returns to the right side against the cool corona.
        val leg = abs(2f * position - 1f)
        val easedLeg = leg * leg * (3f - 2f * leg)
        val moonX = 66f + (if (position < .5f) -18f else 57f) * easedLeg
        val moonY = 36f - 5.5f * sin(position * PI).toFloat()
        circle(canvas, moonX, moonY, 24f, Loft.mix(0xff394358.toInt(), 0xff05050b.toInt(), min(1f, position * 3f)))
        val craters = (1f - position * 4f).coerceIn(0f, 1f)
        if (craters > 0f) {
            val crater = withAlpha(0xff222c40.toInt(), craters)
            circle(canvas, moonX - 8f, moonY - 6f, 5f, crater)
            circle(canvas, moonX + 6f, moonY + 7f, 6f, crater)
            circle(canvas, moonX + 8f, moonY - 11f, 3f, crater)
        }
    }

    private fun fillRound(canvas: Canvas, bounds: RectF, radius: Float, color: Int) {
        paint.color = color
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }

    private fun shade(canvas: Canvas, shader: Shader, bounds: RectF = track, radius: Float = 35f) {
        paint.shader = shader
        paint.alpha = 255
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.shader = null
    }

    private fun circle(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.color = color
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        paint.color = color
        paint.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun withAlpha(color: Int, opacity: Float) =
        (color and 0xffffff) or ((Color.alpha(color) * opacity).toInt() shl 24)

    private fun glow(x: Float, y: Float, radius: Float, core: Int, halo: Int) =
        RadialGradient(x, y, radius, intArrayOf(core, halo, halo and 0xffffff),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)

    private companion object {
        val SCREWS = floatArrayOf(13f, 147f)
        val RAY_X = FloatArray(36) { cos(it * PI / 18).toFloat() }
        val RAY_Y = FloatArray(36) { sin(it * PI / 18).toFloat() }
    }
}
