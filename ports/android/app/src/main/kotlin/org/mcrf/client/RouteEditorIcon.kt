package org.mcrf.client

import android.graphics.*
import android.graphics.drawable.Drawable

/** Small additions to the existing outline vocabulary, without changing shared theme assets. */
internal class RouteEditorIcon(private val kind:String,color:Int,private val density:Float):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun getIntrinsicWidth()=(24*density).toInt()
    override fun getIntrinsicHeight()=(24*density).toInt()
    override fun draw(canvas:Canvas) {
        val saved=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        when(kind) {
            "chain"->{canvas.rotate(-40f,12f,12f);canvas.drawRoundRect(2f,8f,14f,16f,4f,4f,paint);canvas.drawRoundRect(10f,8f,22f,16f,4f,4f,paint)}
            "globe"->{canvas.drawCircle(12f,12f,9f,paint);canvas.drawOval(8f,3f,16f,21f,paint);canvas.drawLine(3f,12f,21f,12f,paint)}
            "apps"->{for(x in listOf(4f,14f))for(y in listOf(4f,14f))canvas.drawRoundRect(x,y,x+6,y+6,1f,1f,paint)}
            else->{canvas.drawLine(4f,12f,20f,12f,paint);canvas.drawLine(14f,6f,20f,12f,paint);canvas.drawLine(14f,18f,20f,12f,paint)}
        }
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
