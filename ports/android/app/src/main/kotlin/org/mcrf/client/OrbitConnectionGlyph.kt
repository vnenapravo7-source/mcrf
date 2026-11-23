package org.mcrf.client

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Eight network outlines, each with four different connection marks. */
internal class OrbitConnectionGlyph(id:String) {
    private val variant=Math.floorMod(id.hashCode(),32)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE;strokeWidth=1.45f
        strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    private val path=Path()

    fun draw(canvas:Canvas,x:Float,y:Float,size:Float,color:Int,alpha:Int){
        val saved=canvas.save()
        canvas.translate(x-size/2,y-size/2)
        canvas.scale(size/24,size/24)
        paint.color=color;paint.alpha=alpha
        fun line(x1:Float,y1:Float,x2:Float,y2:Float)=canvas.drawLine(x1,y1,x2,y2,paint)
        fun circle(x1:Float,y1:Float,r:Float)=canvas.drawCircle(x1,y1,r,paint)
        fun outline(vararg points:Float){
            path.reset();path.moveTo(points[0],points[1])
            for(i in 2 until points.size step 2)path.lineTo(points[i],points[i+1])
            canvas.drawPath(path,paint)
        }
        when(variant/4){
            0->outline(12f,2f,21f,6f,20f,14f,17f,19f,12f,22f,7f,19f,4f,14f,3f,6f,12f,2f)
            1->{canvas.drawRoundRect(3f,5f,21f,19f,5f,5f,paint);line(3f,9f,6f,9f);line(18f,15f,21f,15f)}
            2->{outline(9f,7f,6f,7f,3f,10f,3f,14f,6f,17f,9f,17f)
                outline(15f,7f,18f,7f,21f,10f,21f,14f,18f,17f,15f,17f)}
            3->{circle(12f,3.8f,1.8f);circle(4f,19.5f,1.8f);circle(20f,19.5f,1.8f)
                line(11f,5.5f,5f,17.8f);line(13f,5.5f,19f,17.8f);line(6f,19.5f,18f,19.5f)}
            4->{canvas.drawRoundRect(2.5f,7f,21.5f,17f,2f,2f,paint);line(5f,4f,5f,7f);line(19f,4f,19f,7f)
                line(5f,17f,5f,20f);line(19f,17f,19f,20f)}
            5->{outline(2f,8f,5f,4f,19f,4f,22f,8f,22f,16f,19f,20f,5f,20f,2f,16f,2f,8f)
                line(2f,8f,6f,8f);line(18f,16f,22f,16f)}
            6->{canvas.drawRoundRect(3f,8f,21f,20f,2f,2f,paint)
                canvas.drawArc(7f,1.5f,17f,12f,180f,180f,false,paint);line(7f,6.5f,7f,8f);line(17f,6.5f,17f,8f)}
            else->{outline(3f,6f,7f,6f,7f,18f,3f,18f,3f,6f)
                outline(17f,6f,21f,6f,21f,18f,17f,18f,17f,6f)
                line(7f,9f,10f,9f);line(14f,15f,17f,15f)}
        }
        when(variant%4){
            0->outline(9f,12f,11f,14f,15f,10f)
            1->{line(9f,12f,15f,12f);outline(12.5f,9.5f,15f,12f,12.5f,14.5f)}
            2->{circle(10f,12f,1.2f);circle(14f,12f,1.2f);line(11.2f,12f,12.8f,12f)}
            else->{outline(9f,11f,11f,9f,13f,11f);outline(11f,13f,13f,15f,15f,13f)}
        }
        canvas.restoreToCount(saved)
    }
}
