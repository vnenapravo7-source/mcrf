package org.mcrf.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.ln

/** Two inexpensive flow indicators, not a measurement of channel capacity. */
class SpeedMetersView(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var download=0L
    private var upload=0L
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun update(down:Long,up:Long){
        val d=down.coerceAtLeast(0);val u=up.coerceAtLeast(0)
        if(d==download&&u==upload)return
        download=d;upload=u;invalidate()
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val gap=resources.displayMetrics.density*2
        val segment=((width-gap*7)/8f).coerceAtLeast(0f)
        fun bars(value:Long,y:Float){
            val lit=if(value==0L)0 else (1+7*ln(1+value/1024.0)/ln(1+256*1024.0)).toInt().coerceIn(1,8)
            repeat(8){i->paint.color=if(i<lit)Loft.ACCENT else Loft.LINE
                val x=i*(segment+gap)
                canvas.drawRoundRect(x,y-height*.08f,x+segment,y+height*.08f,gap/2,gap/2,paint)
            }
        }
        bars(download,height*.25f);bars(upload,height*.75f)
    }
}
