package org.mcrf.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.widget.SeekBar
import kotlin.math.abs

/** Native SeekBar still owns value mapping, touch, keyboard and accessibility. */
internal class TurnSliderArt(context: Context, private val budget: Boolean, private val values: List<Int>, private val budgetLimit:Int) : SeekBar(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    private val density = resources.displayMetrics.density
    private val thumb=MotionSpring(0f,900f,50f)
    private val drop=MotionSpring(0f,260f,18f)
    private val lift=MotionSpring(16f,420f,22f)
    private val bubbleScale=MotionSpring(.5f,420f,22f)
    private val thumbScale=MotionSpring(1f,520f,16f)
    private var drag=false
    private var pointer=0f
    private var beforeFrame=0L
    private val baseline=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign=Paint.Align.CENTER
        typeface=android.graphics.Typeface.DEFAULT_BOLD
        textSize=12f*density
    }
    private val labels=values.map(Int::toString)
    private val labelWidths=FloatArray(labels.size) { baseline.measureText(labels[it]) }
    private val labelBaseline=(baseline.ascent()+baseline.descent())/2
    private val limitIndex=values.indexOf(budgetLimit).coerceAtLeast(0)

    init {
        // The platform ripple follows the native thumb hotspot even when custom art draws the slider.
        background = null
        setThumb(null)
        tickMark = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(isEnabled) {
            pointer=event.x
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag=true
                    thumb.stiffness=900f
                    thumb.damping=50f
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                    drag=false
                    thumb.stiffness=300f
                    thumb.damping=14f
                }
            }
            invalidate()
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        beforeFrame=0L
        drag=false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = width - paddingRight.toFloat()
        if (right <= left) return
        val reverse = layoutDirection == LAYOUT_DIRECTION_RTL
        val step = (right - left) / max.coerceAtLeast(1)
        val start = if (reverse) right else left
        val direction = if (reverse) -1 else 1
        val x = start + direction * step * progress
        val y = if(budget)height-18f*density else height-26f*density
        if (hasFocus()) {
            paint.color = Loft.ACCENT
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            canvas.drawRoundRect(density, density, width - density, height - density, 12f * density, 12f * density, paint)
            paint.style = Paint.Style.FILL
        }
        if (budget) drawBudget(canvas,left,right,reverse,y)
        else drawJelly(canvas,start,left,right,x,y)
    }

    private fun drawBudget(canvas:Canvas,left:Float,right:Float,reverse:Boolean,y:Float) {
        val maximum=max.coerceAtLeast(1)
        val selectedFraction=progress.toFloat()/maximum
        val limit=limitIndex.toFloat()/maximum
        val touchFraction=((pointer-left)/(right-left)).coerceIn(0f,1f)
        val fraction=if(drag&&isPressed&&max>0) {
            if(reverse)1f-touchFraction else touchFraction
        } else selectedFraction
        val color=when {
            selectedFraction<=limit->Loft.ERROR
            selectedFraction<=limit+(1f-limit)*.6f->if(Loft.dark)0xffff9800.toInt() else 0xffa54e00.toInt()
            else->if(Loft.dark)0xff0fca79.toInt() else 0xff007f4d.toInt()
        }
        fun point(t:Float)=left+(right-left)*(if(reverse)1f-t else t)
        val bars=41
        val barWidth=minOf(2f*density,(right-left)/bars*.45f)
        for(index in 0 until bars) {
            val t=index.toFloat()/(bars-1)
            val ramp=(1f-abs(t-fraction)*9f).coerceAtLeast(0f)
            val top=y-(8f+20f*ramp*ramp)*density
            val x=point(t)
            paint.color=if(t<=fraction+.002f)Loft.mix(Loft.SURFACE,color,.35f+.65f*(t/fraction.coerceAtLeast(.01f)).coerceAtMost(1f))
                else Loft.LINE
            canvas.drawRoundRect(x-barWidth/2,top,x+barWidth/2,y,barWidth/2,barWidth/2,paint)
        }
        val half=maxOf(14f*density,labelWidths[progress]/2+7f*density).coerceAtMost(width/2f)
        val x=point(fraction).coerceIn(half,width-half)
        val cy=y-42f*density
        paint.color=color
        canvas.drawRoundRect(x-half,cy-10f*density,x+half,cy+10f*density,10f*density,10f*density,paint)
        baseline.color=if(Loft.dark)0xff101010.toInt() else android.graphics.Color.WHITE
        canvas.drawText(labels[progress],x,cy-labelBaseline,baseline)
    }

    private fun drawJelly(canvas:Canvas,start:Float,left:Float,right:Float,target:Float,y:Float) {
        val active=motionStyleAnimationsEnabled(context)&&isShown
        val now=android.os.SystemClock.uptimeMillis()
        if(beforeFrame==0L||!active) {
            thumb.snap(target/density)
            drop.snap(target/density)
        }
        val dt=(now-beforeFrame)/1000f
        beforeFrame=now
        val extra=when {
            pointer<left->-18f*(1f-1f/(1f+(left-pointer)*.55f/(18f*density)))
            pointer>right->18f*(1f-1f/(1f+(pointer-right)*.55f/(18f*density)))
            else->0f
        }
        thumb.target=if(drag)pointer.coerceIn(left,right)/density+extra else target/density
        drop.target=thumb.value
        val lifted=drag||hasFocus()
        lift.target=if(lifted)-38f else 0f
        bubbleScale.target=if(lifted)1f else .5f
        thumbScale.target=if(drag)1.16f else 1f
        if(active) {
            thumb.step(dt)
            drop.step(dt)
            lift.step(dt)
            bubbleScale.step(dt)
            thumbScale.step(dt)
            if(thumb.moving||drop.moving||lift.moving||bubbleScale.moving||thumbScale.moving)postInvalidateOnAnimation()
        } else {
            lift.snap(lift.target)
            bubbleScale.snap(bubbleScale.target)
            thumbScale.snap(1f)
        }
        val x=thumb.value*density
        val groove=3f*density
        val color=if(Loft.dark)Color.WHITE else Loft.INK
        paint.color=Loft.LINE
        canvas.drawRoundRect(left-12*density,y-groove,right+12*density,y+groove,groove,groove,paint)
        paint.color=color
        val railStart=if(start==left)left-12*density else right+12*density
        canvas.drawRoundRect(minOf(railStart,x),y-groove,maxOf(railStart,x),y+groove,groove,groove,paint)
        val stretch=1f+minOf(abs(thumb.velocity)/2400f,.4f)
        val radius=12f*density*thumbScale.value
        canvas.drawOval(x-radius*stretch,y-radius/stretch,x+radius*stretch,y+radius/stretch,paint)
        if(bubbleScale.value>.52f) {
            val cx=drop.value*density
            val cy=y+lift.value*density
            val r=18f*density*bubbleScale.value
            val neck=(1f-abs(cy-y)/(52f*density)).coerceIn(0f,1f)*12f*density
            if(neck>0f) {
                outline.reset()
                outline.moveTo(x-radius*.68f,y)
                outline.cubicTo(x-neck,y-13*density,cx-neck,cy+13*density,cx-r*.68f,cy)
                outline.lineTo(cx+r*.68f,cy)
                outline.cubicTo(cx+neck,cy+13*density,x+neck,y-13*density,x+radius*.68f,y)
                outline.close()
                canvas.drawPath(outline,paint)
            }
            canvas.drawCircle(cx,cy,r,paint)
            baseline.color=Loft.SURFACE
            baseline.alpha=(((bubbleScale.value-.7f)/.25f).coerceIn(0f,1f)*255).toInt()
            canvas.drawText(labels[progress],cx,cy-labelBaseline,baseline)
        }
        for(index in values.indices) {
            val position=left+(right-left)*(if(layoutDirection==LAYOUT_DIRECTION_RTL)1f-index.toFloat()/max.coerceAtLeast(1) else index.toFloat()/max.coerceAtLeast(1))
            val tall=if(index==0||index==values.lastIndex||index==progress)8f else 4f
            paint.color=if(index<=progress)Loft.MUTED else Loft.LINE
            canvas.drawRoundRect(position-.75f*density,y+17f*density,position+.75f*density,y+(17f+tall)*density,density,density,paint)
        }
    }
}
