package org.mcrf.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.animation.PathInterpolator

/** Compact Drive pulse. It reports activity while the owning state supplies the status. */
internal class BeautifulLoadingGrid(context:Context,private val busy:()->Boolean={true}) : AppearanceMotionView(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    private val curve=PathInterpolator(.42f,0f,.58f,1f)
    private var startedAt:Long?=null

    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}

    override fun onDraw(canvas:Canvas) {
        val cell=minOf(4f*density,width/4f,height/4f)
        val gap=cell*.375f
        val extent=3*cell+2*gap
        val left=(width-extent)/2
        val top=(height-extent)/2
        val active=busy()&&motionVisible()
        val now=SystemClock.uptimeMillis()
        if(active&&startedAt==null)startedAt=now
        if(!active)startedAt=null
        val elapsed=startedAt?.let {now-it}?:0L
        paint.color=Loft.INK
        for(row in 0..2)for(column in 0..2) {
            val delay=(column+kotlin.math.abs(row-1))*90
            val phase=if(active)((elapsed-delay).coerceAtLeast(0)%650)/650f else -1f
            val level=when {
                phase<0f->if(row==1&&column==1)1f else .15f
                phase<.18f->.15f+.85f*curve.getInterpolation(phase/.18f)
                phase<.42f->1f
                phase<.62f->1f-.85f*curve.getInterpolation((phase-.42f)/.20f)
                else->.15f
            }
            paint.alpha=(level*255).toInt()
            val x=left+column*(cell+gap)
            val y=top+row*(cell+gap)
            canvas.drawRoundRect(x,y,x+cell,y+cell,cell/4,cell/4,paint)
        }
        if(active)postInvalidateOnAnimation()
    }
}
