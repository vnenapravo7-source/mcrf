package org.mcrf.client

import android.graphics.*
import android.graphics.drawable.Drawable

/** Add with a small clock, matching the route toolbar's outline icons. */
internal class TemporaryRuleIcon(color:Int,private val density:Float):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun getIntrinsicWidth()=(22*density).toInt()
    override fun getIntrinsicHeight()=(22*density).toInt()
    override fun draw(canvas:Canvas) {
        val save=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        canvas.drawLine(3f,9f,15f,9f,paint);canvas.drawLine(9f,3f,9f,15f,paint)
        canvas.drawCircle(17f,17f,5f,paint);canvas.drawLine(17f,14f,17f,17f,paint);canvas.drawLine(17f,17f,19f,18f,paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(colorFilter:ColorFilter?){paint.colorFilter=colorFilter}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
