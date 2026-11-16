package org.mcrf.client

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.min

/** The supplied segmented mark, not a rotated power icon. Coordinates are 254-square. */
object RknMark {
    val LIGHT=0xff008dbe.toInt()
    val DARK=0xff064b80.toInt()
    private val top=floatArrayOf(106f,0f,148f,0f,190f,42f,158f,74f,126f,42f,43f,126f,74f,158f,42f,190f,0f,148f,0f,106f)
    private val lower=FloatArray(top.size){254f-top[it]}
    private val center=floatArrayOf(83f,127f,127f,83f,171f,127f,127f,171f)
    private fun path(points:FloatArray)=Path().apply {moveTo(points[0],points[1]);for(i in 2 until points.size step 2)lineTo(points[i],points[i+1]);close()}
    internal val pieces=listOf(path(top),path(lower),path(center))
    internal val whole=Path().apply {pieces.forEach(::addPath)}
    private val crackPaths=arrayOf(floatArrayOf(72f,72f,90f,83f,81f,102f),floatArrayOf(157f,60f,167f,86f,147f,99f),floatArrayOf(127f,94f,118f,121f,137f,143f),floatArrayOf(55f,139f,69f,151f,62f,176f),floatArrayOf(185f,134f,161f,147f,174f,174f),floatArrayOf(113f,184f,130f,170f,144f,189f),floatArrayOf(97f,36f,119f,46f,128f,24f),floatArrayOf(181f,198f,161f,185f,153f,215f)).map {points->Path().apply {moveTo(points[0],points[1]);for(i in 2 until points.size step 2)lineTo(points[i],points[i+1])}}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
    fun draw(c:Canvas,x:Float,y:Float,size:Float,outline:Boolean=true,flag:Boolean=false,cracks:Int=0,only:Int=-1,tint:Int?=null) {
        if(size<=0f)return
        val saved=c.save();c.translate(x-size/2,y-size/2);val scale=size/254f;c.scale(scale,scale)
        if(flag){
            val clipped=c.save();c.clipPath(whole);paint.style=Paint.Style.FILL
            listOf(0xfff4f5f7.toInt(),0xff1768b8.toInt(),0xffd64248.toInt()).forEachIndexed {i,color->paint.color=color;c.drawRect(0f,i*254f/3,254f,(i+1)*254f/3,paint)}
            c.restoreToCount(clipped)
        }else pieces.forEachIndexed {i,p->if(only<0||only==i){
            val color=tint?:if(i==0)LIGHT else DARK
            paint.style=if(outline)Paint.Style.STROKE else Paint.Style.FILL
            if(outline){paint.color=Color.argb(45,Color.red(color),Color.green(color),Color.blue(color));paint.strokeWidth=7f/scale;c.drawPath(p,paint)}
            paint.color=color;paint.strokeWidth=1.6f/scale;c.drawPath(p,paint)
        }}
        if(cracks>0){
            val clipped=c.save();c.clipPath(whole);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f/scale;paint.color=Loft.INK
            repeat(min(cracks,crackPaths.size)){c.drawPath(crackPaths[it],paint)}
            c.restoreToCount(clipped)
        }
        c.restoreToCount(saved)
    }
}
