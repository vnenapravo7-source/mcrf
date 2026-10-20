package org.mcrf.client

import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.Context
import android.content.res.ColorStateList
import kotlin.math.*

object Loft {
    @JvmField var BACKGROUND = 0xfff3eadc.toInt()
    @JvmField var SURFACE = 0xfffffbf2.toInt()
    @JvmField var FIELD = 0xffe9dfcf.toInt()
    @JvmField var LINE = 0xffc9bead.toInt()
    @JvmField var INK = 0xff2e2823.toInt()
    @JvmField var MUTED = 0xff665b50.toInt()
    @JvmField var ACCENT = 0xff006467.toInt()
    @JvmField var SUCCESS = ACCENT
    @JvmField var ERROR = 0xffb43e3e.toInt()
    @JvmField var dark = false
    const val GRAPHITE = 0xff21152f.toInt()
    const val ON_DARK = 0xfff5faf9.toInt()
    const val SOFT_ACCENT = 0xffdcefee.toInt()
    @JvmStatic fun selection() = HomeUiTheme.palette(dark).selected
    @JvmStatic fun onAccent() = HomeUiTheme.palette(dark).onAccent
    @JvmStatic fun ripple() = HomeUiTheme.ripple(dark)
    @JvmStatic fun rootBackground(context:Context):Drawable = Backdrop(context.resources.displayMetrics.density)
    @JvmStatic @JvmOverloads fun glass(context:Context,radius:Int=20):Drawable = Glass(context.resources.displayMetrics.density,radius.toFloat())
    @JvmStatic fun connectionFill():Int=(SURFACE and 0x00ffffff) or (179 shl 24)
    /** Thirty percent transparent fill; text, logo and animation retain full opacity. */
    @JvmStatic fun connectionSurface(context:Context):Drawable = GradientDrawable().apply {
        val density=context.resources.displayMetrics.density
        setColor(connectionFill())
        cornerRadius=20*density
        setStroke(kotlin.math.max(1,density.roundToInt()),HomeUiTheme.glassEdge(dark))
    }
    @JvmStatic fun fieldBackground(context:Context):Drawable = GradientDrawable().apply {
        setColor(translucent(FIELD));cornerRadius=12*context.resources.displayMetrics.density
        setStroke(context.resources.displayMetrics.density.roundToInt().coerceAtLeast(1),LINE)
    }
    @JvmStatic @JvmOverloads fun buttonBackground(context:Context,primary:Boolean=false,selected:Boolean=false):Drawable {
        val density=context.resources.displayMetrics.density
        fun surface(disabled:Boolean,focused:Boolean=false)=GradientDrawable().apply {
            val base=if(primary)ACCENT else if(selected)selection()else SURFACE
            setColor((base and 0x00ffffff) or ((if(disabled)128 else 230) shl 24));cornerRadius=14*density
            setStroke((density*(if(focused)2 else 1)).roundToInt().coerceAtLeast(1),if(focused||selected)ACCENT else LINE)
        }
        val states=android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),surface(true))
            addState(intArrayOf(android.R.attr.state_focused),surface(false,true))
            addState(intArrayOf(),surface(false))
        }
        return RippleDrawable(ColorStateList.valueOf(ripple()),states,null)
    }
    /** Flat grouped-list rows: only the container owns rounded corners and borders. */
    @JvmStatic fun rowBackground(context:Context,selected:Boolean):Drawable = RippleDrawable(
        ColorStateList.valueOf(ripple()),
        android.graphics.drawable.ColorDrawable(if(selected)translucent(selection())else Color.TRANSPARENT),
        android.graphics.drawable.ColorDrawable(Color.WHITE))
    /** Only the fill is translucent: labels and icons retain full contrast. */
    @JvmStatic fun translucent(color:Int):Int = (color and 0x00ffffff) or (218 shl 24)
    /** Modal content must not show readable text from the screen underneath. */
    @JvmStatic fun dialogSurface():Int = (SURFACE and 0x00ffffff) or (255 shl 24)
    @JvmStatic fun popupBackground(context:Context):Drawable = GradientDrawable().apply {
        setColor(dialogSurface());cornerRadius=12*context.resources.displayMetrics.density
        setStroke(context.resources.displayMetrics.density.roundToInt().coerceAtLeast(1),LINE)
    }
    @JvmStatic fun warpIcon(context:Context,color:Int=ACCENT):Drawable = Icon("warp",color,context.resources.displayMetrics.density)
    @JvmStatic fun mix(first: Int, second: Int, amount: Float): Int {
        val value = amount.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a+(b-a)*value).roundToInt()
        return Color.argb(channel(Color.alpha(first), Color.alpha(second)), channel(Color.red(first), Color.red(second)), channel(Color.green(first), Color.green(second)), channel(Color.blue(first), Color.blue(second)))
    }
    @JvmStatic @JvmOverloads fun pingColor(delay: Long?, goodLimit: Long = PingStatus.GOOD_LIMIT_MS): Int = when (PingStatus.tone(delay,goodLimit)) {
        PingStatus.Tone.UNKNOWN -> MUTED
        PingStatus.Tone.GOOD -> if (dark) 0xff80dcb1.toInt() else 0xff156749.toInt()
        PingStatus.Tone.HIGH -> if (dark) 0xffe5be6c.toInt() else 0xff84580a.toInt()
        PingStatus.Tone.UNAVAILABLE -> if (dark) 0xffff999f.toInt() else 0xffa72e36.toInt()
    }
    @JvmStatic fun pingSurface(delay: Long?) = SURFACE
    @JvmStatic fun apply(night: Boolean) {
        dark = night
        val theme=HomeUiTheme.palette(night)
        BACKGROUND=theme.background;SURFACE=theme.surface;FIELD=theme.field;LINE=theme.line
        INK=theme.ink;MUTED=theme.muted;ACCENT=theme.accent
        SUCCESS = ACCENT; ERROR = if (night) 0xffffaaa1.toInt() else 0xffb43e3e.toInt()
    }
    /** Abstract backdrop is static: no GPU blur of text, no background animation loop. */
    class Backdrop(private val density:Float=1f) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            if(b.width()<=0||b.height()<=0)return
            val theme=HomeUiTheme.palette(dark)
            paint.shader=LinearGradient(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat(),intArrayOf(theme.background,mix(theme.background,theme.end,.58f),theme.end),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
            canvas.drawRect(b,paint);paint.shader=null
            val saved = canvas.save(); canvas.clipRect(b)
            fun orb(x: Float, y: Float, radius: Float, color: Int) {
                paint.shader = RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(x, y, radius, paint); paint.shader = null
            }
            val glow=Color.argb(HomeUiTheme.glowAlpha(dark),Color.red(theme.glow),Color.green(theme.glow),Color.blue(theme.glow))
            orb(b.left+b.width()*.92f,b.top+b.height()*.16f,b.width()*.85f,glow)
            orb(b.left+b.width()*.12f,b.top+b.height()*.68f,b.width()*.75f,Color.argb(HomeUiTheme.glowAlpha(dark,secondary=true),Color.red(theme.glow),Color.green(theme.glow),Color.blue(theme.glow)))
            canvas.restoreToCount(saved)
            PatternWallpaper.draw(canvas,b,density)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        override fun getOpacity() = PixelFormat.OPAQUE
    }
    class Glass(private val density: Float, private val radius: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds; val edge = density*.5f
            paint.style = Paint.Style.FILL
            paint.shader=null;paint.color=SURFACE
            canvas.drawRoundRect(b.left+edge, b.top+edge, b.right-edge, b.bottom-edge, radius*density, radius*density, paint)
            paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
            paint.color = mix(SURFACE,LINE,.55f)
            canvas.drawRoundRect(b.left+edge, b.top+edge, b.right-edge, b.bottom-edge, radius*density, radius*density, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    class PowerSurface(private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var active = false; private var pending = false;private var failed=false
        fun state(active: Boolean, pending: Boolean,failed:Boolean=false) { if (active != this.active || pending != this.pending||failed!=this.failed) { this.active = active; this.pending = pending;this.failed=failed; invalidateSelf() } }
        override fun draw(canvas: Canvas) {
            val b = bounds; val inset = density; val radius = min(b.height(), b.width())/2f-inset
            paint.style = Paint.Style.FILL
            val base = if(failed)ERROR else if(active)0xff32b875.toInt() else ACCENT
            paint.color=translucent(SURFACE)
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE; paint.strokeWidth = density*3
            paint.color = base
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, paint)
            if(pending){paint.color=INK;canvas.drawArc(b.left+density*3,b.top+density*3,b.right-density*3,b.bottom-density*3,-90f,85f,false,paint)}
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    class Dial(private val outer: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var active = false; private var connecting = false; private var opacity = 255
        fun state(active: Boolean, connecting: Boolean) {
            if (this.active == active && this.connecting == connecting) return
            this.active = active; this.connecting = connecting; invalidateSelf()
        }
        override fun draw(canvas: Canvas) {
            val b = bounds; val x = b.exactCenterX(); val y = b.exactCenterY(); val radius = min(b.width(), b.height()) / 2f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = max(1f, radius / 90); paint.color = if (outer) LINE else GRAPHITE; paint.alpha = opacity
            if (outer) {
                val outerRadius = radius - max(4f, radius / 25f)
                canvas.drawCircle(x, y, outerRadius, paint)
                val saved = canvas.save(); canvas.translate(x, y)
                repeat(4) { canvas.drawLine(0f, -outerRadius + 4, 0f, -outerRadius + 8, paint); canvas.rotate(90f) }
                canvas.restoreToCount(saved)
                paint.color = if (active) ACCENT else if (dark) MUTED else GRAPHITE; paint.alpha = opacity
                paint.style = Paint.Style.FILL; canvas.drawCircle(x, y - outerRadius, max(2f, radius / 36), paint)
            } else {
                paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, radius - 2, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = max(1f, radius / 60)
                paint.color = if (active) 0xff5ecfc6.toInt() else 0xff52676a.toInt(); paint.alpha = opacity
                canvas.drawCircle(x, y, radius - 4, paint)
                if (connecting) { paint.color = 0xff5ecfc6.toInt(); canvas.drawArc(x-radius+4, y-radius+4, x+radius-4, y+radius-4, -90f, 80f, false, paint) }
            }
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    class Icon(private val kind: String, private val color: Int, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var opacity = 255
        override fun getIntrinsicWidth() = (22 * density).roundToInt()
        override fun getIntrinsicHeight() = (22 * density).roundToInt()
        override fun draw(canvas: Canvas) {
            val saved = canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width()/24f, bounds.height()/24f)
            paint.color = color; paint.alpha = opacity; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            fun path(vararg points: Number) { val p = Path(); p.moveTo(points[0].toFloat(), points[1].toFloat()); for (i in 2 until points.size step 2) p.lineTo(points[i].toFloat(), points[i+1].toFloat()); canvas.drawPath(p, paint) }
            fun line(x: Number,y: Number,x2: Number,y2: Number) = canvas.drawLine(x.toFloat(),y.toFloat(),x2.toFloat(),y2.toFloat(),paint)
            fun circle(x: Number,y: Number,r: Number) = canvas.drawCircle(x.toFloat(),y.toFloat(),r.toFloat(),paint)
            fun knob(x: Float,y: Float) { paint.style = Paint.Style.FILL; canvas.drawCircle(x,y,3.5f,paint); paint.style = Paint.Style.STROKE }
            when (kind) {
                "rkn" -> RknMark.draw(canvas,12f,12f,22f,outline=false,tint=Color.argb(opacity,Color.red(color),Color.green(color),Color.blue(color)))
                "home" -> { path(3,10,12,3,21,10); path(5,9,5,21,19,21,19,9); path(10,21,10,15,14,15,14,21) }
                "servers" -> { canvas.drawRoundRect(4f,3f,20f,10f,2f,2f,paint); canvas.drawRoundRect(4f,14f,20f,21f,2f,2f,paint); circle(7,6.5f,.3f); circle(7,17.5f,.3f); line(12,6.5f,17,6.5f); line(12,17.5f,17,17.5f) }
                "proxy" -> { circle(5,12,2); circle(19,12,2); line(7,12,17,12); path(10,4,14,4,14,8); path(14,20,10,20,10,16) }
                "telegram" -> {path(2,10,22,3,17,22,10,16,2,10);path(10,16,16,9);path(10,16,10,21,13,18)}
                "warp", "cloudflare" -> { val p=Path().apply {moveTo(4f,16f);cubicTo(1f,16f,1f,10f,6f,10f);cubicTo(6f,3f,16f,3f,17f,10f);cubicTo(23f,8f,24f,16f,20f,16f);lineTo(4f,16f)};canvas.drawPath(p,paint);path(4,19,20,19) }
                "routes" -> { path(5,20,5,13,19,13,19,4); path(15,8,19,4,23,8); circle(5,4,2); line(5,6,5,8); circle(19,20,2); line(19,16,19,18) }
                "deploy" -> { path(9,15,9,8,12,3,15,8,15,15,9,15); circle(12,9,1.4f); path(9,11,5,15,5,18,9,16); path(15,11,19,15,19,18,15,16); path(10,18,12,21,14,18) }
                "settings" -> { line(4,6,20,6); line(4,12,20,12); line(4,18,20,18); knob(8f,6f); knob(16f,12f); knob(10f,18f) }
                "gear" -> { line(5,3,5,21);line(12,3,12,21);line(19,3,19,21);canvas.drawRoundRect(2f,7f,8f,11f,1f,1f,paint);canvas.drawRoundRect(9f,14f,15f,18f,1f,1f,paint);canvas.drawRoundRect(16f,5f,22f,9f,1f,1f,paint) }
                "sun" -> {circle(12,12,4);repeat(8){val angle=it*PI/4;line(12+cos(angle)*7,12+sin(angle)*7,12+cos(angle)*9,12+sin(angle)*9)}}
                "moon" -> {val p=Path().apply {moveTo(18f,17f);cubicTo(8f,22f,1f,9f,11f,4f);cubicTo(8f,11f,12f,17f,18f,17f)};canvas.drawPath(p,paint)}
                "logs" -> { canvas.drawRoundRect(3f,4f,21f,20f,3f,3f,paint);path(7,9,10,12,7,15);line(13,15,17,15) }
                "power" -> { canvas.drawArc(4f,4f,20f,20f,-55f,290f,false,paint); line(12,2,12,11) }
                "stop" -> canvas.drawRoundRect(6f,6f,18f,18f,2f,2f,paint)
                "shield", "shield-active" -> { path(12,2,21,6,20,14,17,19,12,22,7,19,4,14,3,6,12,2); if (kind == "shield-active") path(8,12,11,15,16,9) }
                "add" -> { line(12,5,12,19); line(5,12,19,12) }
                "close" -> { path(6,6,18,18); path(18,6,6,18) }
                "arrow" -> path(9,5,16,12,9,19)
                "back" -> {path(11,5,4,12,11,19);line(4,12,21,12)}
                "up" -> path(5,15,12,8,19,15)
                "down" -> path(5,9,12,16,19,9)
                "check" -> path(5,12,10,17,19,6)
                "refresh" -> { canvas.drawArc(4f,4f,20f,20f,35f,280f,false,paint); path(16,3,21,4,20,9) }
                "file" -> { path(6,3,14,3,19,8,19,21,6,21,6,3); path(14,3,14,8,19,8) }
                "folder" -> path(3,7,3,20,21,20,21,7,11,7,9,4,3,4,3,7)
                "clipboard" -> { canvas.drawRoundRect(5f,5f,19f,21f,2f,2f,paint); path(9,5,9,2,15,2,15,5,9,5); line(8,10,16,10); line(8,14,16,14) }
                "copy" -> {canvas.drawRoundRect(8f,8f,21f,21f,2f,2f,paint);path(16,5,16,3,3,3,3,16,5,16)}
                "download" -> {path(12,3,12,16);path(7,11,12,16,17,11);path(4,17,4,21,20,21,20,17)}
                "trash" -> {path(4,6,20,6);path(7,6,8,21,16,21,17,6);path(9,6,9,3,15,3,15,6);line(10,10,10,17);line(14,10,14,17)}
                "edit" -> { path(4,20,5,15,16,4,20,8,9,19,4,20); line(14,6,18,10) }
                "share", "share-shield" -> { circle(5,12,2); circle(18,5,2); circle(18,19,2); line(7,11,16,6); line(7,13,16,18) }
                "ping" -> path(2,12,6,12,9,5,13,19,16,12,22,12)
                "more" -> { paint.style = Paint.Style.FILL; circle(12,5,1.4f); circle(12,12,1.4f); circle(12,19,1.4f) }
                "qr", "qr-shield" -> { canvas.drawRect(3f,3f,9f,9f,paint); canvas.drawRect(15f,3f,21f,9f,paint); canvas.drawRect(3f,15f,9f,21f,paint); path(15,15,18,15,18,18,21,18,21,21,15,21,15,18) }
                else -> circle(12,12,8)
            }
            if(kind=="share-shield"||kind=="qr-shield") {
                val badge=Path().apply {moveTo(17f,12f);lineTo(23f,14f);lineTo(22f,19f);lineTo(17f,23f);lineTo(12f,19f);lineTo(11f,14f);close()}
                paint.style=Paint.Style.FILL;paint.color=Loft.SURFACE;canvas.drawPath(badge,paint)
                paint.style=Paint.Style.STROKE;paint.color=color;paint.strokeWidth=1.4f;canvas.drawPath(badge,paint)
                path(14,17,16,19,20,15)
            }
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
